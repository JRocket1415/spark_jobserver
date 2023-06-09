package spark.jobserver

import java.net.MalformedURLException
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.BeforeAndAfterAll
import spark.jobserver.JobManagerActor.JobKilledException
import spark.jobserver.io.JobDAOActor._
import spark.jobserver.io._
import spark.jobserver.util.ErrorData

import java.time.{Instant, ZoneId, ZonedDateTime}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// Tests web response codes and formatting
// Does NOT test underlying Supervisor / JarManager functionality
class WebApiSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll
with ScalatestRouteTest with ScalaFutures with SprayJsonSupport {
  import scala.collection.JavaConverters._

  def actorRefFactory: ActorSystem = system

  val bindConfKey = "spark.jobserver.bind-address"
  val bindConfVal = "0.0.0.0"
  val masterConfKey = "spark.master"
  val masterConfVal = "spark://localhost:7077"
  val config = ConfigFactory.parseString(s"""
    spark {
      master = "$masterConfVal"
      jobserver.bind-address = "$bindConfVal"
      jobserver.short-timeout = 3 s
    }
    akka.http.server {}
                                 """)

  val dummyPort = 9999

  // See http://doc.akka.io/docs/akka/2.2.4/scala/actors.html#Deprecated_Variants;
  // for actors declared as inner classes we need to pass this as first arg
  val dummyActor = system.actorOf(Props(classOf[DummyActor], this))
  lazy val daoConfig = ConfigFactory.load("local.test.dao.conf")
  val jobDaoActor = system.actorOf(JobDAOActor.props(new InMemoryMetaDAO, new InMemoryBinaryObjectsDAO, daoConfig))
  val statusActor = system.actorOf(JobStatusActor.props(jobDaoActor))

  val api = new WebApi(system, config, dummyPort, dummyActor, dummyActor, dummyActor, dummyActor, null)
  val routes = api.myRoutes

  val dt = Instant.parse("2013-05-29T00:00:00Z").atZone(ZoneId.of("UTC"))
  val binaryInfo = BinaryInfo("demo", BinaryType.Jar, dt)
  val baseJobInfo = JobInfo("foo-1", "cid", "context", "com.abc.meme", JobStatus.Running, dt,
      None, None, Seq(binaryInfo))
  val finishedJobInfo = baseJobInfo.copy(endTime = Some(dt.plusMinutes(5)), state = JobStatus.Finished)
  val errorJobInfo = finishedJobInfo.copy(
      error = Some(ErrorData(new Throwable("test-error"))), state = JobStatus.Error)
  val killedJobInfo = finishedJobInfo.copy(
      error = Some(ErrorData(JobKilledException(finishedJobInfo.jobId))), state = JobStatus.Killed)
  val JobId = "jobId"
  val StatusKey = "status"
  val ResultKey = "result"

  val contextInfo = ContextInfo("contextId", "contextWithInfo", "config", Some("address"), dt, None,
      ContextStatus.Running, None)
  val finishedContextInfo = contextInfo.copy(name = "finishedContextWithInfo",
      endTime = Some(dt.plusMinutes(5)), state = ContextStatus.Finished)

  class DummyActor extends Actor {

    import CommonMessages._
    import ContextSupervisor._
    import JobManagerActor._

    def getStateBasedOnEvents(events: Set[Class[_]]): String = {
       events.find(_ == classOf[JobStarted]) match {
          case Some(_) => JobStatus.Started
          case _ => JobStatus.Running
       }
    }

    def receive: Receive = customReceive

    def customReceive: Receive = {
      case GetJobInfo("_mapseq") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("_mapseq") =>
        val map = Map("first" -> Seq(1, 2, Seq("a", "b")))
        sender ! JobResult(map)
      case GetJobInfo("_mapmap") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("_mapmap") =>
        val map = Map("second" -> Map("K" -> Map("one" -> 1)))
        sender ! JobResult(map)
      case GetJobInfo("_seq") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("_seq") =>
        sender ! JobResult(Seq(1, 2, Map("3" -> "three")))
      case GetJobResult("_stream") =>
        sender ! JobResult("\"1, 2, 3, 4, 5, 6, 7\"".getBytes().toStream)
      case GetJobInfo("_stream") =>
        sender ! Some(finishedJobInfo)
      case GetJobInfo("_num") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("_num") =>
        sender ! JobResult(5000)
      case GetJobInfo("_unk") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("_unk") => sender ! JobResult(Seq(1, math.BigInt(101)))
      case GetJobInfo("_running") =>
        sender ! Some(baseJobInfo)
      case GetJobResult("_running") =>
        sender ! baseJobInfo
      case GetJobInfo("_finished") =>
        sender ! Some(finishedJobInfo)
      case GetJobInfo("_no_status") =>
        sender ! None
      case GetJobInfo("fail_to_fetch_result") =>
        sender ! Some(finishedJobInfo)
      case GetJobResult("fail_to_fetch_result") =>
        sender ! JobResult(None)
      case GetJobInfo("job_to_kill") => sender ! Some(baseJobInfo)
      case GetJobInfo("wrong_job_id") => sender ! None
      case GetJobInfo("job_kill_with_error") => sender ! Some(errorJobInfo)
      case GetJobInfo("already_killed") => sender ! Some(killedJobInfo)
      case GetJobInfo(id) => sender ! Some(baseJobInfo)
      case GetJobResult(id) => sender ! JobResult(id + "!!!")
      case GetJobInfos(limitOpt, statusOpt) => {
        statusOpt match {
          case Some(JobStatus.Error) => sender ! JobInfos(Seq(errorJobInfo))
          case Some(JobStatus.Killed) => sender ! JobInfos(Seq(killedJobInfo))
          case Some(JobStatus.Finished) => sender ! JobInfos(Seq(finishedJobInfo))
          case Some(JobStatus.Running) => sender ! JobInfos(Seq(baseJobInfo))
          case _ => sender ! JobInfos(Seq(baseJobInfo, finishedJobInfo))
        }
      }

      case ListBinaries(Some(BinaryType.Jar)) =>
        sender ! Map("demo1" -> (BinaryType.Jar, dt), "demo2" -> (BinaryType.Jar, dt.plusHours(1)))

      case ListBinaries(_) =>
        sender ! Map(
          "demo1" -> (BinaryType.Jar, dt),
          "demo2" -> (BinaryType.Jar, dt.plusHours(1)),
          "demo3" -> (BinaryType.Egg, dt.plusHours(2))
        )
      case GetBinary("demo") => sender ! LastBinaryInfo(Some(binaryInfo))
      case GetBinary(_) => sender ! LastBinaryInfo(None)
      // Ok these really belong to a JarManager but what the heck, type unsafety!!
      case StoreBinary("badjar", _, _) => sender ! InvalidBinary
      case StoreBinary("daofail", _, _) => sender ! BinaryStorageFailure(new Exception("DAO failed to store"))
      case StoreBinary(_, _, _) => sender ! BinaryStored

      case DeleteBinary("badbinary") => sender ! NoSuchBinary("badbinary")
      case DeleteBinary("active") => sender ! BinaryInUse(Seq("job-active"))
      case DeleteBinary("failure") => sender ! BinaryDeletionFailure(new Exception("deliberate"))
      case DeleteBinary(_) => sender ! BinaryDeleted

      case DataManagerActor.StoreData("errorfileToRemove", _) => sender ! DataManagerActor.Error
      case DataManagerActor.StoreData(filename, _) => {
        sender ! DataManagerActor.Stored(filename + "-time-stamp")
      }
      case DataManagerActor.ListData => sender ! Set("demo1", "demo2")
      case DataManagerActor.DeleteData("/tmp/fileToRemove") => sender ! DataManagerActor.Deleted
      case DataManagerActor.DeleteData("errorfileToRemove") => sender ! DataManagerActor.Error
      case GetBinaryInfoListForCp(cp) if cp.contains("BinaryNotFound") =>
        sender ! NoSuchBinary("BinaryNotFound")
      case GetBinaryInfoListForCp(Seq("Failure")) =>
        sender ! GetBinaryInfoListForCpFailure(new Exception("failed"))
      case GetBinaryInfoListForCp(cp) =>
        sender ! BinaryInfoListForCp(cp.map(name => binaryInfo.copy(appName = name)))

      case ListContexts => sender ! Seq("context1", "context2")
      case StopContext("none", _) => sender ! NoSuchContext
      case StopContext("timeout-ctx", _) => sender ! ContextStopError(new Throwable("Some Throwable"))
      case StopContext("unexp-err", _) => sender ! UnexpectedError
      case StopContext("ctx-stop-in-progress", _) => sender ! ContextStopInProgress
      case StopContext(_, _) => sender ! ContextStopped
      case AddContext("one", _) => sender ! ContextAlreadyExists
      case AddContext("custom-ctx", c) =>
        // see WebApiMainRoutesSpec => "context routes" =>
        // "should setup a new context with the correct configurations."
        c.getInt("test") should be(1)
        c.getInt("num-cpu-cores") should be(2)
        c.getInt("override_me") should be(3)
        sender ! ContextInitialized
      case AddContext("initError-ctx", _) => sender ! ContextInitError(new Throwable("Some Throwable"))
      case AddContext("initError-URI-ctx", _) =>
        sender ! ContextInitError(new MalformedURLException("Some Throwable"))
      case AddContext("unexp-err", _) => sender ! UnexpectedError
      case AddContext(_, _) => sender ! ContextInitialized

      case GetContext("no-context") => sender ! NoSuchContext
      case GetContext(_) => sender ! (self)

      case StartAdHocContext(_, _) => sender ! (self)

      // These routes are part of JobManagerActor
      case StartJob("no-class", _, _, _, _, _) => sender ! NoSuchClass
      case StartJob(_, cp, config, events, _, _) =>
        cp.map(_.appName).head match {
          case "no-app" => sender ! NoSuchFile ("no-app")
          case "wrong-type" => sender ! WrongJobType
          case "err" => sender ! JobErroredOut (
            "foo", dt, new RuntimeException ("oops", new IllegalArgumentException ("foo") ) )
          case "loadErr" => sender ! JobLoadingError(new MalformedURLException("foo"))
          case "foo" | "demo" =>
            statusActor ! Subscribe("foo", sender, events)
            val jobInfo = JobInfo(
              "foo", "cid", "context", "com.abc.meme",
              getStateBasedOnEvents(events), dt, None, None, Seq.empty)
            statusActor ! JobStatusActor.JobInit(jobInfo)
            statusActor ! JobStarted(jobInfo.jobId, jobInfo)
            if (events.contains(classOf[JobFinished])) sender ! JobFinished(jobInfo.jobId, ZonedDateTime.now())
            statusActor ! Unsubscribe("foo", sender)
          case "context-already-stopped" => sender ! ContextStopInProgress
        }
      case GetJobConfig("badjobid") => sender ! JobConfig(None)
      case GetJobConfig(_) => sender ! JobConfig(Some(config))

      case SaveJobConfig(_, _) => sender ! JobConfigStored
      case KillJob(jobId) => sender ! JobKilled(jobId, ZonedDateTime.now())

      case GetSparkContexData("context1") => sender ! SparkContexData(
        "context1", Some("local-1337"), Some("http://spark:4040"))
      case GetSparkContexData("context2") => sender ! SparkContexData("context2", Some("local-1337"), None)
      case GetSparkContexData("unexp-err") => sender ! UnexpectedError

      // On a GetSparkContexData LocalContextSupervisorActor returns only name of the context, api and url,
      // AkkaClusterSupervisorActor returns whole contextInfo instead.
      // Adding extra cases to test both
      case GetSparkContexData("contextWithInfo") => sender ! SparkContexData(
        contextInfo, Some("local-1337"), Some("http://spark:4040"))
      case GetSparkContexData("finishedContextWithInfo") => sender ! SparkContexData(
        finishedContextInfo, None, None)
    }
  }

  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(1, Seconds))

  override def beforeAll(): Unit = {
    api.start()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
