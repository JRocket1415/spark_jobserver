package spark.jobserver

import akka.actor._
import akka.pattern._
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spark.jobserver.CommonMessages._
import spark.jobserver.context.JavaStreamingContextFactory
import spark.jobserver.io.JobDAOActor.GetJobInfo
import spark.jobserver.io.{InMemoryBinaryObjectsDAO, InMemoryMetaDAO, JobDAOActor, JobInfo, JobStatus}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConverters._

object JavaStreamingSpec extends JobSpecConfig {
  override val contextFactory = classOf[JavaStreamingContextFactory].getName
}

class JavaStreamingSpec extends ExtrasJobSpecBase(JavaStreamingSpec.getNewSystem) {

  implicit private val futureTimeout = Timeout(5.seconds)
  private val emptyConfig = ConfigFactory.parseMap(
    Map("streaming.batch_interval" -> 3).asJava).withFallback(
    ConfigFactory.parseString("cp = [\"demo\"]"))
  private val classPrefix = "spark.jobserver."
  private val streamingJob = classPrefix + "JStreamingTestJob"

  private def cfg = JavaStreamingSpec.getContextConfig(false, JavaStreamingSpec.contextConfig)

  before {
    inMemoryMetaDAO = new InMemoryMetaDAO
    inMemoryBinDAO = new InMemoryBinaryObjectsDAO
    daoActor = system.actorOf(JobDAOActor.props(inMemoryMetaDAO, inMemoryBinDAO, daoConfig))
    manager = system.actorOf(JobManagerActor.props(daoActor))
    supervisor = TestProbe().ref
  }

  after {
    Await.result(gracefulStop(manager, 5 seconds), 5 seconds) // stop context
  }

  describe("Running Java based Streaming Jobs") {
    it("Should return Correct results") {
      manager ! JobManagerActor.Initialize(cfg, emptyActor)
      expectMsgClass(10 seconds, classOf[JobManagerActor.Initialized])

      val binInfo = uploadTestJar()
      manager ! JobManagerActor.StartJob(streamingJob, Seq(binInfo), emptyConfig, asyncEvents ++ errorEvents)
      val id = expectMsgPF(6 seconds, "No?") {
        case JobStarted(jid, _) =>
          jid should not be null
          jid
      }
      Thread.sleep(1000)
      val info = Await.result(daoActor ? GetJobInfo(id), 60 seconds).asInstanceOf[Option[JobInfo]]
      info.get match {
        case JobInfo(_, _, _, _, state, _, _, _, _, _) if state == JobStatus.Running => {}
        case e => fail(s":-( No worky work $e")
      }
    }
  }
}
