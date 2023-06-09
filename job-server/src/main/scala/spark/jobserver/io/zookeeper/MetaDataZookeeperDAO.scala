package spark.jobserver.io.zookeeper

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory
import spark.jobserver.io._
import spark.jobserver.util.{JsonProtocols, Utils}

import java.time.{Instant, ZonedDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MetaDataZookeeperDAO {
  val binariesDir = "/binaries"
  val contextsDir = "/contexts"
  val jobsDir = "/jobs"
}

class MetaDataZookeeperDAO(config: Config) extends MetaDataDAO {
  import JsonProtocols._
  import MetaDataZookeeperDAO._

  private val logger = LoggerFactory.getLogger(getClass)
  private val zookeeperUtils = new ZookeeperUtils(config)

  /*
   * Contexts
   */

  override def saveContext(contextInfo: ContextInfo): Future[Boolean] = {
    logger.debug(s"Saving context ${contextInfo.id} (state: ${contextInfo.state})")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val id = contextInfo.id
          zookeeperUtils.write(client, contextInfo, s"$contextsDir/$id")
      }
    }
  }

  override def getContext(id: String): Future[Option[ContextInfo]] = {
    logger.debug(s"Retrieving context $id")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val path = s"$contextsDir/$id"
          zookeeperUtils.sync(client, path)
          zookeeperUtils.read[ContextInfo](client, path) match {
            case Some(contextInfo) =>
              Some(contextInfo)
            case None =>
              logger.info(s"Didn't find any context information for the path: $path")
              None
          }
      }
    }
  }

  override def getContextByName(name: String): Future[Option[ContextInfo]] = {
    logger.debug(s"Retrieving context by name $name")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, contextsDir)
          zookeeperUtils.list(client, contextsDir)
            .flatMap(id => zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id"))
            .filter(_.name == name)
            .sortBy(_.startTime.toInstant)
            .lastOption
      }
    }
  }

  override def getContexts(limit: Option[Int], statuses: Option[Seq[String]]): Future[Seq[ContextInfo]] = {
    logger.debug("Retrieving multiple contexts")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, contextsDir)
          lazy val allContexts = zookeeperUtils.list(client, contextsDir)
            .flatMap(id => zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id"))
          lazy val filteredContexts = statuses match {
            case None => allContexts
            case Some(allowed) => allContexts.filter(c => allowed.contains(c.state))
          }
          val sortedContexts = filteredContexts
            .sortBy(_.startTime.toInstant)(Ordering[Instant].reverse)
          limit match {
            case None =>
              sortedContexts
            case Some(l) =>
              sortedContexts.take(l)
          }
      }
    }
  }

  override def deleteFinalContextsOlderThan(olderThan: ZonedDateTime): Future[Boolean] = {
    logger.debug(s"Deleting context older than ${olderThan}")
    Future{
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, contextsDir)
          lazy val toBeDeleted = zookeeperUtils.list(client, contextsDir)
            .flatMap(id => zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id"))
            .filter(contextInfo => ContextStatus.getFinalStates().contains(contextInfo.state))
            .filter(contextInfo => contextInfo.endTime.isDefined
              && contextInfo.endTime.get.isBefore(olderThan))
          var success = true
          toBeDeleted.foreach( context => {
            zookeeperUtils.delete(client, s"${contextsDir}/${context.id}") match {
              case true => logger.trace(s"Deleted context ${context.name} (${context.id})")
              case false =>
                success = false
                logger.error(s"Could not delete context ${context.name} (${context.id})")
            }
          })
          success
      }
    }
  }

  /*
   * Jobs
   */

  override def saveJob(jobInfo: JobInfo): Future[Boolean] = {
    logger.debug(s"Saving job ${jobInfo.jobId} (state: ${jobInfo.state})")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val jobId = jobInfo.jobId
          zookeeperUtils.write(client, jobInfo, s"$jobsDir/$jobId")
      }
    }
  }

  override def getJob(id: String): Future[Option[JobInfo]] = {
    logger.debug(s"Retrieving job $id")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, s"$jobsDir/$id")
          readJobInfo(client, id)
      }
    }
  }

  override def getJobs(limit: Int, status: Option[String]): Future[Seq[JobInfo]] = {
    logger.debug("Retrieving multiple jobs")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, jobsDir)
          lazy val allJobs = zookeeperUtils.list(client, jobsDir)
            .flatMap(id => readJobInfo(client, id))
          lazy val filteredJobs =
            status match {
              case None => allJobs
              case Some(allowed) => allJobs.filter(_.state == allowed)
            }
          filteredJobs.sortBy(_.startTime.toInstant)(Ordering[Instant].reverse)
            .take(limit)
      }
    }
  }

  override def getJobsByContextId(contextId: String, statuses: Option[Seq[String]]): Future[Seq[JobInfo]] = {
    logger.debug(s"Retrieving jobs by contextId $contextId")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, jobsDir)
          lazy val allJobs = zookeeperUtils.list(client, jobsDir)
            .flatMap(id => readJobInfo(client, id))
            .filter(_.contextId == contextId)
          lazy val filteredJobs =
            statuses match {
              case None => allJobs
              case Some(allowed) => allJobs.filter(j => allowed.contains(j.state))
            }
          filteredJobs.sortBy(_.startTime.toInstant)(Ordering[Instant].reverse)
      }
    }
  }

  override def getFinalJobsOlderThan(olderThan: ZonedDateTime): Future[Seq[JobInfo]] = {
    logger.debug(s"Retrieving jobs older than ${olderThan}")
    Future{
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, jobsDir)
          zookeeperUtils.list(client, jobsDir)
            .flatMap(id => zookeeperUtils.read[JobInfo](client, s"$jobsDir/$id"))
            .filter(jobInfo => JobStatus.getFinalStates().contains(jobInfo.state))
            .filter(jobInfo => jobInfo.endTime.isDefined
              && jobInfo.endTime.get.isBefore(olderThan))
      }
    }
  }

  override def deleteJobs(jobIds: Seq[String]): Future[Boolean] = {
    logger.debug(s"Deleting ${jobIds.size} jobs")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          var success = true
          jobIds.foreach( jobId => {
            zookeeperUtils.delete(client, s"${jobsDir}/${jobId}")
            match {
              case true => logger.trace(s"Deleted job ${jobId}")
              case false => success = false
                logger.error(s"Could not delete job ${jobId}")
            }
          })
          success
      }
    }
  }


  /*
   * Helpers
   */

  private def readJobInfo(client: CuratorFramework, jobId: String): Option[JobInfo] = {
    zookeeperUtils.read[JobInfo](client, s"$jobsDir/$jobId") match {
      case None =>
        logger.info(s"Didn't find any job information for the path: $jobsDir/$jobId")
        None
      case Some(j) => {
        Some(j)
      }
    }

  }

  /*
   * JobConfigs
   */

  override def saveJobConfig(jobId: String, config: Config): Future[Boolean] = {
    logger.debug(s"Saving job config for job $jobId")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val configRender = config.root().render(ConfigRenderOptions.concise())
          zookeeperUtils.write(client, configRender, s"$jobsDir/$jobId/config")
      }
    }
  }

  override def getJobConfig(jobId: String): Future[Option[Config]] = {
      Future {
        readJobConfig(jobId)
      }
  }

  def readJobConfig(jobId: String): Option[Config] = {
    logger.debug(s"Retrieving job config for $jobId")
    Utils.usingResource(zookeeperUtils.getClient) {
      client =>
        val path = s"$jobsDir/$jobId/config"
        zookeeperUtils.sync(client, path)
        zookeeperUtils.read[String](client, path)
          .flatMap(render => Some(ConfigFactory.parseString(render)))
    }
  }

  /*
   * Binaries
   */

  override def getBinary(name: String): Future[Option[BinaryInfo]] = {
    logger.debug(s"Retrieving binary $name")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val path = s"$binariesDir/$name"
          zookeeperUtils.sync(client, path)
          zookeeperUtils.read[Seq[BinaryInfo]](client, path) match {
            case Some(infoForBinary) =>
              Some(infoForBinary
                .sortWith(_.uploadTime.toInstant.toEpochMilli > _.uploadTime.toInstant.toEpochMilli)
                .head)
            case None =>
              None
          }
      }
    }
  }

  override def getBinaries: Future[Seq[BinaryInfo]] = {
    logger.debug("Retrieving all binaries")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, binariesDir)
          zookeeperUtils.list(client, binariesDir).map(
            name => zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name"))
            .filter(_.isDefined)
            .map(binary => binary.get.head)
      }
    }
  }

  override def getBinariesByStorageId(storageId: String): Future[Seq[BinaryInfo]] = {
    logger.debug(s"Retrieving binaries for storage id $storageId")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.sync(client, binariesDir)
          zookeeperUtils.list(client, binariesDir).flatMap(
            name => zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name")
            .getOrElse(Seq.empty[BinaryInfo]))
            .filter(_.binaryStorageId.getOrElse("") == storageId)
            .groupBy(_.appName).map(_._2.head).toSeq
      }
    }
  }

  override def saveBinary(name: String, binaryType: BinaryType,
                          uploadTime: ZonedDateTime, binaryStorageId: String): Future[Boolean] = {
    logger.debug(s"Saving binary $name")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val oldInfoForBinary = zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name")
          val newBinary = BinaryInfo(name, binaryType, uploadTime, Some(binaryStorageId))
          zookeeperUtils.write[Seq[BinaryInfo]](client,
            newBinary +: oldInfoForBinary.getOrElse(Seq.empty[BinaryInfo]),
            s"$binariesDir/$name")
      }
    }
  }

  override def deleteBinary(name: String): Future[Boolean] = {
    logger.debug(s"Deleting binary $name")
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name") match {
            case Some(_) =>
              zookeeperUtils.delete(client, s"$binariesDir/$name")
            case None =>
              logger.info(s"Didn't find any information for the path: $binariesDir/$name")
              false
          }
      }
    }
  }

  /**
    * Gets the jobs by binary name.
    *
    * The behavior of this function differs from other dao implementations.
    * In other dao's, if user deletes a binary and then tries to fetch the
    * associated job then jobserver will not return anything. This is due to
    * the join between Jobs & binaries table. This behavior is not correct.
    *
    * Whereas in Zookeeper dao, user can still get the jobs even if the
    * binaries are deleted.
    * @param binName Name of binary
    * @param statuses List of job statuses
    * @return Sequence of all job infos matching query
    */
  override def getJobsByBinaryName(binName: String, statuses: Option[Seq[String]] = None):
      Future[Seq[JobInfo]] = {
      Future {
        Utils.usingResource(zookeeperUtils.getClient) {
          client =>
            logger.debug(s"Retrieving jobs by binary name $binName (states: $statuses)")
            zookeeperUtils.sync(client, binariesDir)
            lazy val jobsUsingBinName = zookeeperUtils.list(client, jobsDir)
              .flatMap(id => readJobInfo(client, id))
              .filter(j => j.cp.map(_.appName).contains(binName))
            statuses match {
              case None =>
                jobsUsingBinName
              case Some(states) =>
                jobsUsingBinName.filter(j => states.contains(j.state))
            }
        }
      }
  }
}
