package spark.jobserver

import akka.actor.{ActorNotFound, ActorRef, ActorSystem, Address, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.cluster.Cluster
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import java.io.File
import java.util.concurrent.{TimeUnit, TimeoutException}
import spark.jobserver.io._
import spark.jobserver.util._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.collection.mutable.ListBuffer
import com.google.common.annotations.VisibleForTesting

import java.time.ZonedDateTime
import scala.util.control.NonFatal

/**
 * The Spark Job Server is a web service that allows users to submit and run Spark jobs, check status,
 * and view results.
 * It may offer other goodies in the future.
 * It only takes in one optional command line arg, a config file to override the default (and you can still
 * use -Dsetting=value to override)
 * -- Configuration --
 * {{{
 *   spark {
 *     master = "local"
 *     jobserver {
 *       port = 8090
 *     }
 *   }
 * }}}
 */
object JobServer {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global

  class InvalidConfiguration(error: String) extends RuntimeException(error)

  val ACTOR_SYSTEM_NAME = "JobServer"

  // Allow custom function to create ActorSystem.  An example of why this is useful:
  // we can have something that stores the ActorSystem so it could be shut down easily later.
  def start(args: Array[String], makeSystem: Config => ActorSystem) {
    // For Akka DowningProvider to decide which node to down in case of split brain,
    // it is important to understand weather it's SJS master or driver node. Therefore new value in config.
    val defaultConfig = ConfigFactory.load().withValue(
      JobServerRoles.propertyName, ConfigValueFactory.fromAnyRef(JobServerRoles.jobserverMaster))
    val config = if (args.length > 0) {
      val configFile = new File(args(0))
      if (!configFile.exists()) {
        println("Could not find configuration file " + configFile)
        sys.exit(1)
      }
      ConfigFactory.parseFile(configFile).withFallback(defaultConfig).resolve()
    } else {
      defaultConfig
    }
    logger.info("Starting JobServer with config {}", config.getConfig("spark").root.render())
    logger.info("Akka HTTP config: {}", config.getConfig("akka.http.server").root.render())

    val system = makeSystem(config)
    val port = config.getInt("spark.jobserver.port")
    val sparkMaster = config.getString("spark.master")
    val driverMode = config.getString("spark.submit.deployMode")
    val contextPerJvm = config.getBoolean("spark.jobserver.context-per-jvm")
    val superviseModeEnabled = config.getBoolean("spark.driver.supervise")
    val akkaTcpPort = config.getInt("akka.remote.netty.tcp.port")
    val daoCleanupEnabled = config.hasPath("spark.jobserver.dao-cleanup-after")

    // ensure context-per-jvm is enabled
    if (sparkMaster.startsWith("yarn") && !contextPerJvm) {
      throw new InvalidConfiguration("YARN mode requires context-per-jvm")
    } else if (sparkMaster.startsWith("mesos") && !contextPerJvm) {
      throw new InvalidConfiguration("Mesos mode requires context-per-jvm")
    } else if (driverMode == "cluster" && !contextPerJvm) {
      throw new InvalidConfiguration("Cluster mode requires context-per-jvm")
    }

    // TODO: This should be removed once auto-discovery is introduced in SJS
    checkIfAkkaTcpPortSpecifiedForSuperviseMode(driverMode, superviseModeEnabled, akkaTcpPort)

    if (config.getString(JobserverConfig.BINARY_DAO_CONFIG_PATH) == JobserverConfig.BINARY_SQL_DAO_CLASS||
      config.getString(JobserverConfig.METADATA_DAO_CONFIG_PATH) == JobserverConfig.METADATA_SQL_DAO_CLASS) {

      if (contextPerJvm) {
        // Check if we are using correct DB backend when context-per-jvm is enabled.
        // H2 mem is not supported.
        if (config.getString("spark.jobserver.sqldao.jdbc.url").startsWith("jdbc:h2:mem")) {
          throw new InvalidConfiguration("H2 mem backend is not support with context-per-jvm.")
        }
      }

      // cluster mode requires network base H2 server
      if (driverMode == "cluster") {
        val jdbcUrl = config.getString("spark.jobserver.sqldao.jdbc.url")
        if (jdbcUrl.startsWith("jdbc:h2") && !jdbcUrl.startsWith("jdbc:h2:tcp")
          && !jdbcUrl.startsWith("jdbc:h2:ssl")) {
          throw new InvalidConfiguration(
            """H2 backend and cluster mode is not supported with file or in-memory storage,
               use tcp or ssl server.""")
        }
      }
    }

    // start embedded H2 server
    if (config.getBoolean("spark.jobserver.startH2Server")) {
      val rootDir = config.getString("spark.jobserver.sqldao.rootdir")
      val h2 = org.h2.tools.Server.createTcpServer("-tcpAllowOthers", "-baseDir", rootDir).start();
      logger.info("Embeded H2 server started with base dir {} and URL {}", rootDir, h2.getURL: Any)
    }

    val haSeedNodes = getAndValidateHASeedNodes(config)

    // Create DAO objects
    val metadataDaoClass = Class.forName(config.getString(JobserverConfig.METADATA_DAO_CONFIG_PATH))
    val binaryDaoClass = Class.forName(config.getString(JobserverConfig.BINARY_DAO_CONFIG_PATH))
    val ctorMeta = metadataDaoClass.getDeclaredConstructor(Class.forName("com.typesafe.config.Config"))
    val ctorBin = binaryDaoClass.getDeclaredConstructor(Class.forName("com.typesafe.config.Config"))
    val metaDataDAO = try {
      ctorMeta.newInstance(config).asInstanceOf[MetaDataDAO]
    } catch {
      case _: ClassNotFoundException =>
        throw new InvalidConfiguration("Couldn't create Metadata DAO instance: please check configuration")
    }
    val binaryDAO = try {
      ctorBin.newInstance(config).asInstanceOf[BinaryObjectsDAO]
    } catch {
      case _: ClassNotFoundException =>
        throw new InvalidConfiguration("Couldn't create Binary DAO instance: please check configuration")
    }

    // Start actors and managers
    val daoActor = system.actorOf(Props(classOf[JobDAOActor], metaDataDAO, binaryDAO,
      config, daoCleanupEnabled), "dao-manager")
    val dataFileDAO = new DataFileDAO(config)
    val dataManager = system.actorOf(Props(classOf[DataManagerActor], dataFileDAO), "data-manager")
    val binManager = system.actorOf(Props(classOf[BinaryManager], daoActor), "binary-manager")

    startCleanupIfEnabled(config)

    // Add initial job JARs, if specified in configuration.
    storeInitialBinaries(config, binManager)

    val webApiPF = new WebApi(system, config, port, binManager, dataManager, _: ActorRef, daoActor,
        _: HealthCheck)
    contextPerJvm match {
      case false =>
        val supervisor = system.actorOf(Props(classOf[LocalContextSupervisorActor],
            daoActor, dataManager), AkkaClusterSupervisorActor.ACTOR_NAME)
        supervisor ! ContextSupervisor.AddContextsFromConfig  // Create initial contexts
        webApiPF(supervisor, getHealthCheckInstance(supervisor, daoActor, config)).start()
      case true =>
        val cluster = Cluster(system)
        setupAkkaClusterHooks(system)

        // Check if all contexts marked as running are still available and get the ActorRefs
        val existingManagerActorRefs = getExistingManagerActorRefs(system, daoActor)
        joinAkkaCluster(cluster, existingManagerActorRefs, haSeedNodes)
        // We don't want to read all the old events that happened in the cluster
        // So, we remove the initialStateMode parameter
        cluster.registerOnMemberUp {
          system.actorOf(AkkaClusterSupervisorActor.managerProps(daoActor, dataManager, cluster),
              "singleton")
          val proxy = system.actorOf(AkkaClusterSupervisorActor.proxyProps(system),
              "context-supervisor-proxy")

          proxy ! ContextSupervisor.AddContextsFromConfig  // Create initial contexts
          webApiPF(proxy, getHealthCheckInstance(proxy, daoActor, config)).start()
        }
    }
  }

  def getHealthCheckInstance(supervisor: ActorRef, daoActor: ActorRef, config: Config): HealthCheck = {
    val healthClass = Class.forName(config.getString("spark.jobserver.healthcheck"))
    var healthCheckInst: HealthCheck = null
    if (healthClass.getName() == "spark.jobserver.util.ActorsHealthCheck") {
      val healthCtr = healthClass.getConstructors()(0)
      healthCheckInst = healthCtr.newInstance(supervisor, daoActor).asInstanceOf[HealthCheck]
    } else {
      healthCheckInst = healthClass.newInstance.asInstanceOf[HealthCheck]
    }
    healthCheckInst
  }

  /**
    * This function is responsible for joining the Akka cluster dynamically
    * based on current situation of Akka cluster.
    *
    * - If there are no old slave nodes and HA section of config is not
    *   defined, then this JVM joins itself.
    * - If some slave nodes are already running then this JVM joins them.
    *   If multiple jobserver master VMs will be started then all of them
    *   will join the slave nodes.
    * - If no slave nodes are available but ha nodes are provided, then
    *   this JVM joins the master nodes. joinSeedNodes function is
    *   intelligent enough to form 1 cluster if multiple masters are
    *   specified and they all join at the same time (given that the
    *   list of masters is the same on all nodes)
    *
    * @param cluster Current Akka cluster
    * @param slaveSeedNodes Akka addresses of currently running seed nodes
    * @param haSeedNodes Akka addresses of all jobserver masters
    */
  @VisibleForTesting
  def joinAkkaCluster(cluster: Cluster, slaveSeedNodes: List[ActorRef], haSeedNodes: List[Address]) {
    if (slaveSeedNodes.isEmpty && haSeedNodes.isEmpty) {
      val selfAddress = cluster.selfAddress
      logger.info(s"Joining newly created cluster at ${selfAddress}")
      cluster.join(selfAddress)
    } else {
      val slaveAddressList = slaveSeedNodes.map(_.path.address)
      logger.info(s"Found slave seed nodes: ${slaveAddressList.mkString(", ")} / " +
        s"HA seed Nodes: ${haSeedNodes.mkString(", ")}")
      val allSeedNodes = haSeedNodes ::: slaveAddressList
      cluster.joinSeedNodes(allSeedNodes)
    }
  }

  @VisibleForTesting
  def getManagerActorRef(contextInfo: ContextInfo, system: ActorSystem): Option[ActorRef] = {
    val duration = FiniteDuration(3, SECONDS)
    val clusterAddress = contextInfo.actorAddress.getOrElse(return None)
    val address = clusterAddress + "/user/" + JobserverConfig.MANAGER_ACTOR_PREFIX +
        contextInfo.id
    try {
      val actorResolveFuture = system.actorSelection(address).resolveOne(duration)
      val resolvedActorRef = Await.result(actorResolveFuture, duration)
      logger.info(s"Found context ${contextInfo.name} -> reconnect is possible")
      Some(resolvedActorRef)
    } catch {
      case ex @ (_: ActorNotFound | _: TimeoutException | _: InterruptedException) =>
        logger.error(s"Failed to resolve actor reference for context ${contextInfo.name}", ex.getMessage)
        None
      case ex: Exception =>
        logger.error("Unexpected exception occurred", ex)
        None
    }
  }

  def setupAkkaClusterHooks(system: ActorSystem): Unit = {
    system.registerOnTermination {
      logger.info("Actor system terminated. Exiting the JVM with exit code 0.")
      System.exit(0)
    }

    // Exit JVM if actor system was removed from the cluster. Jobserver Web API can't function
    // correctly without connection to other nodes.
    Cluster(system).registerOnMemberRemoved {
      new Thread {
        override def run(): Unit = {
          val actorSystemTerminationTimeout = 60.seconds
          if (Try(Await.ready(system.whenTerminated, actorSystemTerminationTimeout)).isFailure) {
            logger.info("Actor system shutdown took to long, exit JVM with exit code 0.")
            System.exit(0)
          }
        }
      }.start()

      logger.info("Actor system left cluster. Trigger shutdown of actor system and exit JVM.")
      system.terminate().wait() // blocking call
    }
  }

  @VisibleForTesting
  def setReconnectionFailedForContextAndJobs(contextInfo: ContextInfo,
      jobDaoActor: ActorRef, timeout: Timeout) {
    val ctxName = contextInfo.name
    val logMsg = s"Reconnecting to context $ctxName failed ->" +
      s"updating status of context $ctxName and related jobs to error"
    logger.info(logMsg)
    val updatedContextInfo = contextInfo.copy(endTime = Option(ZonedDateTime.now()),
        state = ContextStatus.Error, error = Some(ContextReconnectFailedException()))
    jobDaoActor ! JobDAOActor.SaveContextInfo(updatedContextInfo)
    try{
      Await.ready((jobDaoActor ? JobDAOActor.GetJobInfosByContextId(
          contextInfo.id, Some(JobStatus.getNonFinalStates())))(timeout), timeout.duration).value.get match {
        case Success(JobDAOActor.JobInfos(jobInfos)) =>
          jobInfos.foreach(jobInfo => {
          jobDaoActor ! JobDAOActor.SaveJobInfo(jobInfo.copy(state = JobStatus.Error,
              endTime = Some(ZonedDateTime.now()),
              error = Some(ErrorData(ContextReconnectFailedException()))))
          })
        case Success(unknownResponse) =>
          logger.error(s"Received unexpected response: $unknownResponse")
        case Failure(e: Exception) =>
          logger.error(s"Exception occurred while fetching jobs for context (${contextInfo.id})", e)
      }
    } catch {
      case _ : TimeoutException =>
        logger.error(s"Fetching job infos for context ${contextInfo.id} timed out. "
            + "Not updating jobs to error state.")
    }
  }

  @VisibleForTesting
  def getExistingManagerActorRefs(system: ActorSystem, jobDaoActor: ActorRef): List[ActorRef] = {
    val validManagerRefs = new ListBuffer[ActorRef]()
    val config = system.settings.config
    val daoAskTimeout = Timeout(config.getDuration("spark.jobserver.dao-timeout", TimeUnit.SECONDS).second)
    val resp = Await.result(
        (jobDaoActor ? JobDAOActor.GetContextInfos(None, Some(
          Seq(ContextStatus.Running, ContextStatus.Stopping))))(daoAskTimeout).
        mapTo[JobDAOActor.ContextInfos], daoAskTimeout.duration)

    resp.contextInfos.map{ contextInfo =>
      getManagerActorRef(contextInfo, system) match {
        case None => setReconnectionFailedForContextAndJobs(contextInfo, jobDaoActor, daoAskTimeout)
        case Some(actorRef) => validManagerRefs += actorRef
      }
    }
    validManagerRefs.toList
  }

  private def parseInitialBinaryConfig(key: String, config: Config): Map[String, String] = {
    if (config.hasPath(key)) {
      val initialJarsConfig = config.getConfig(key).root
      logger.info("Adding initial job jars: {}", initialJarsConfig.render())
      initialJarsConfig
        .asScala
        .map { case (key, value) => (key, value.unwrapped.toString) }
        .toMap
    } else {
      Map()
    }
  }

  private def storeInitialBinaries(config: Config, binaryManager: ActorRef): Unit = {
    val legacyJarPathsKey = "spark.jobserver.job-jar-paths"
    val initialBinPathsKey = "spark.jobserver.job-bin-paths"
    val initialBinaries = parseInitialBinaryConfig(legacyJarPathsKey, config) ++
      parseInitialBinaryConfig(initialBinPathsKey, config)
    if(initialBinaries.nonEmpty) {
      // Ensure that the jars exist
      for (binPath <- initialBinaries.values) {
        val f = new java.io.File(binPath)
        if (!f.exists) {
          val msg =
            if (f.isAbsolute) {
              s"Initial Binary File $binPath does not exist"
            } else {
              s"Initial Binary File $binPath (${f.getAbsolutePath}) does not exist"
            }

          throw new java.io.IOException(msg)
        }
      }

      val initialBinariesWithTypes = initialBinaries.mapValues {
        case s if s.endsWith("." + BinaryType.Jar.extension) => (BinaryType.Jar, s)
        case s if s.endsWith("." + BinaryType.Egg.extension) => (BinaryType.Egg, s)
        case s if s.endsWith("." + BinaryType.Wheel.extension) => (BinaryType.Wheel, s)
        case other =>
          throw new Exception(s"Only Jars (with extension .jar), Python Egg packages (with extension .egg) " +
            s"and Python Wheel packages (with extension .whl) are supported. Found $other")
      }

      val contextCreationTimeout = util.SparkJobUtils.getContextCreationTimeout(config)
      val future =
        (binaryManager ? StoreLocalBinaries(initialBinariesWithTypes))(contextCreationTimeout.seconds)

      Await.result(future, contextCreationTimeout.seconds) match {
        case InvalidBinary => sys.error("Could not store initial job binaries.")
        case BinaryStorageFailure(ex) =>
          logger.error("Failed to store initial binaries", ex)
          sys.error(s"Failed to store initial binaries: ${ex.getMessage}")
        case _ =>
      }
    }
  }

  private def checkIfAkkaTcpPortSpecifiedForSuperviseMode(driverMode: String,
      superviseModeEnabled: Boolean, akkaTcpPort: Int) {
    if (driverMode == "cluster" && superviseModeEnabled == true && akkaTcpPort == 0) {
      throw new InvalidConfiguration("Supervise mode requires akka.remote.netty.tcp.port to be hardcoded")
    }
  }

  private def startCleanupIfEnabled(config: Config): Unit = {
    isCleanupEnabled(config) match {
      case true =>
        logger.info("Cleanup of dao is enabled. Instantiating the class.")
        Try(doStartupCleanup(config)) match {
          case Success(true) => logger.info("Cleaned the dao successfully.")
          case Success(false) => logger.error("Dao cleanup failed.")
          case Failure(e) => logger.error("Failed to cleanup", e)
        }
      case false => logger.info("Startup dao cleanup is disabled.")
    }
  }

  private def getAndValidateHASeedNodes(config: Config): List[Address] = {
    try {
      Utils.getHASeedNodes(config)
    } catch {
      case NonFatal(t) =>
        logger.error("Failed to fetch HA seed nodes", t)
        throw new InvalidConfiguration("Wrong format for config section spark.jobserver.ha.masters")
    }
  }

  @VisibleForTesting
  def isCleanupEnabled(config: Config): Boolean = {
    val daoClassProperty = "spark.jobserver.startup_dao_cleanup_class"
    (config.hasPath(daoClassProperty) && config.getString(daoClassProperty) != "")
  }

  @VisibleForTesting
  def doStartupCleanup(config: Config): Boolean = {
    val daoCleanupClass = Class.forName(config.getString("spark.jobserver.startup_dao_cleanup_class"))
    val ctor = daoCleanupClass.getDeclaredConstructor(Class.forName("com.typesafe.config.Config"))
    val daoCleanup = ctor.newInstance(config).asInstanceOf[DAOCleanup]
    daoCleanup.cleanup()
  }

  def main(args: Array[String]) {
    import scala.collection.JavaConverters._
    def makeSupervisorSystem(name: String)(config: Config): ActorSystem = {
      val configWithRole = config.withValue("akka.cluster.roles",
        ConfigValueFactory.fromIterable(List("supervisor").asJava))
      ActorSystem(name, configWithRole)
    }

    try {
      start(args, makeSupervisorSystem(ACTOR_SYSTEM_NAME)(_))
    } catch {
      case e: Exception =>
        logger.error("Unable to start Spark JobServer: ", e)
        sys.exit(1)
    }
  }
}
