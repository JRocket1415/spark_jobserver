package spark.jobserver.io.zookeeper

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfter
import spark.jobserver.io._
import spark.jobserver.util.{ContextJVMInitializationTimeout, ContextReconnectFailedException, CuratorTestCluster, ErrorData, ResolutionFailedOnStopContextException, Utils}
import spark.jobserver.TestJarFinder

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.scalatest.funspec.{ AnyFunSpec, AnyFunSpecLike }
import org.scalatest.matchers.should.Matchers

class MetaDataZookeeperDAOSpec extends AnyFunSpec with TestJarFinder with AnyFunSpecLike
      with Matchers with BeforeAndAfter {

  /*
   * Setup
   */

  private val timeout = 60 seconds
  private val testServer = new CuratorTestCluster()

  def config: Config = ConfigFactory.parseString(
    s"""
         |spark.jobserver.zookeeperdao.connection-string = "${testServer.getConnectString}"
    """.stripMargin
  ).withFallback(
    ConfigFactory.load("local.test.dao.conf")
  )

  private var dao = new MetaDataZookeeperDAO(config)
  private val zkUtils = new ZookeeperUtils(config)

  before {
    Utils.usingResource(zkUtils.getClient) {
      client =>
        zkUtils.delete(client, "/")
    }
  }

  def toDateTime(millis: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis),
    ZoneId.systemDefault())

  /*
   * Test data
   */

  // Binaries
  val binJar = BinaryInfo("binaryWithJar", BinaryType.Jar, ZonedDateTime.now().withNano(0),
      Some(BinaryObjectsDAO.calculateBinaryHashString("1".getBytes)))
  val binEgg = BinaryInfo("binaryWithEgg", BinaryType.Egg, ZonedDateTime.now().withNano(0),
      Some(BinaryObjectsDAO.calculateBinaryHashString("2".getBytes)))
  val binWheel = BinaryInfo("binaryWithWheel", BinaryType.Wheel, ZonedDateTime.now().withNano(0),
      Some(BinaryObjectsDAO.calculateBinaryHashString("3".getBytes)))
  val binURI = BinaryInfo("http://foo/bar", BinaryType.URI, ZonedDateTime.now().withNano(0),
      Some(BinaryObjectsDAO.calculateBinaryHashString("42".getBytes)))
  val binJarV2 = BinaryInfo("binaryWithJar", BinaryType.Jar, ZonedDateTime.now().withNano(0).plusHours(1),
      Some(BinaryObjectsDAO.calculateBinaryHashString("3".getBytes)))
  val binElse = BinaryInfo("anotherBinaryWithJar", BinaryType.Jar, ZonedDateTime.now().withNano(0),
      Some(BinaryObjectsDAO.calculateBinaryHashString("3".getBytes)))

  // Contexts
  val normalContext = ContextInfo("someId", "someName", "someConfig", Some("ActorAddress"),
      toDateTime(1548683342369L), Some(toDateTime(1548683342370L)), "someState",
      Some(new Throwable("message")))
  val sameIdContext = ContextInfo("someId", "someOtherName", "someOtherConfig",
      Some("OtherActorAddress"), toDateTime(1548683342369L),
      Some(toDateTime(1548683342370L)), "someOtherState", Some(new Throwable("otherMessage")))
  val minimalContext = new ContextInfo("someOtherId", "someName", "someOtherconfig", None,
      toDateTime(1548683342368L), None, "someState", None)
  val anotherStateContext = new ContextInfo("anotherId", "anotherName", "someOtherconfig", None,
      toDateTime(1548683342368L).plusHours(1), None, "someState", None)

  // Jobs
  val normalJob = JobInfo("someJobId", "someContextId", "someContextName",
      "someClassPath", "someState", ZonedDateTime.now().withNano(0), Some(ZonedDateTime.now().withNano(0)),
      Some(ErrorData("someMessage", "someError", "someTrace")), Seq(binJar))
  val minimalJob = JobInfo("someOtherJobId", "someContextId", "someOtherContextName",
      "someClassPath", "someState", ZonedDateTime.now().withNano(0).plusHours(1), None, None, Seq(binJar))
  val sameIdJob = JobInfo("someJobId", "someOtherContextId", "thirdContextName",
      "someClassPath", "someState", ZonedDateTime.now().withNano(0).minusHours(1), None, None, Seq(binJar))
  val anotherJob = JobInfo("thirdJobId", "someOtherContextId", "thirdContextName",
      "someClassPath", "anotherState", ZonedDateTime.now().withNano(0).minusHours(1), None, None, Seq(binJar))
  val multiJarJob = JobInfo("multiJarJobId", "someOtherContextId", "thirdContextName",
    "someClassPath", "anotherState", ZonedDateTime.now().withNano(0).minusHours(1), None, None,
    Seq(binJar, binEgg, binWheel, binURI))

  // JobConfigs
  val config1 = ConfigFactory.parseString("{key : value}")
  val config2 = ConfigFactory.parseString("{key : value2}")

  /*
   * Tests: Binaries
   */

  describe("Binary tests") {

    it("should save and retrieve binaries") {
      // JAR
      var success = Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
          binJar.uploadTime, binJar.binaryStorageId.get), timeout)
      success should equal(true)
      val binJarStored = Await.result(dao.getBinary(binJar.appName), timeout)
      binJarStored should equal(Some(binJar))
      // EGG
      success = Await.result(dao.saveBinary(binEgg.appName, binEgg.binaryType,
          binEgg.uploadTime, binEgg.binaryStorageId.get), timeout)
      success should equal(true)
      val binEggStored = Await.result(dao.getBinary(binEgg.appName), timeout)
      binEggStored should equal(Some(binEgg))
      // WHEEL
      success = Await.result(dao.saveBinary(binWheel.appName, binWheel.binaryType,
        binWheel.uploadTime, binWheel.binaryStorageId.get), timeout)
      success should equal(true)
      val binWheelStored = Await.result(dao.getBinary(binWheel.appName), timeout)
      binWheelStored should equal(Some(binWheel))
    }

    it("should retrieve the last uploaded binary") {
      // Save two binaries with same name (one older)
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binJarV2.appName, binJarV2.binaryType, binJarV2.uploadTime,
          binJarV2.binaryStorageId.get), timeout) should equal(true)
      // Get the older one
      val binStored = Await.result(dao.getBinary(binJarV2.appName), timeout)
      binStored should equal(Some(binJarV2))
    }

    it("should return none if there is no binary with that name") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      val binStored = Await.result(dao.getBinary("nonexisting name"), timeout)
      binStored should equal(None)
    }

    it("should list all (oldest) binaries") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binJarV2.appName, binJarV2.binaryType, binJarV2.uploadTime,
          binJarV2.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binEgg.appName, binEgg.binaryType, binEgg.uploadTime,
          binEgg.binaryStorageId.get), timeout) should equal(true)
      val allBins = Await.result(dao.getBinaries, timeout)
      allBins.toSet should equal(Set(binJarV2, binEgg))
    }

    it("should return empty list if there are no binaries") {
      val allBins = Await.result(dao.getBinaries, timeout)
      allBins should equal(Seq.empty[BinaryInfo])
    }

    it("should delete binaries") {
      // Insert three binaries
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binJarV2.appName, binJarV2.binaryType, binJarV2.uploadTime,
          binJarV2.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binEgg.appName, binEgg.binaryType, binEgg.uploadTime,
          binEgg.binaryStorageId.get), timeout) should equal(true)
      // Delete two with same name
      Await.result(dao.deleteBinary(binJar.appName), timeout) should equal(true)
      // Assertions
      Await.result(dao.getBinaries, timeout) should equal(Seq(binEgg))
      Await.result(dao.getBinary(binJar.appName), timeout) should equal(None)
      Await.result(dao.getBinary(binEgg.appName), timeout) should equal(Some(binEgg))
    }

    it("should delete nothing if deleting a non-existing object") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.deleteBinary("nonexisting name"), timeout) should equal(false)
      Await.result(dao.getBinaries, timeout) should equal(Seq(binJar))
      Await.result(dao.getBinary(binJar.appName), timeout) should equal(Some(binJar))
    }

    it("should return all binaries given a storage id") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binJarV2.appName, binJarV2.binaryType, binJarV2.uploadTime,
          binJarV2.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binEgg.appName, binEgg.binaryType, binEgg.uploadTime,
          binEgg.binaryStorageId.get), timeout) should equal(true)
      Await.result(dao.saveBinary(binElse.appName, binElse.binaryType, binElse.uploadTime,
          binElse.binaryStorageId.get), timeout) should equal(true)
      val binsOnStor3 = Await.result(dao.getBinariesByStorageId(
          BinaryObjectsDAO.calculateBinaryHashString("3".getBytes)), timeout)
      binsOnStor3.toSet should equal(Set(binJarV2, binElse))
    }

    it("should list all binaries with given storage id with unique (name, binaryStorageId)") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
        ZonedDateTime.now().withNano(0), binJar.binaryStorageId.get), timeout)
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
        binJar.uploadTime, binJar.binaryStorageId.get), timeout)
      Await.result(dao.saveBinary(binJar.appName + "2", binJar.binaryType,
        binJar.uploadTime, "anotherStorageId"), timeout)
      val resultList = Await.result(dao.getBinariesByStorageId(binJar.binaryStorageId.get), timeout)
      resultList.length should equal(1)
      resultList.head.appName should equal(binJar.appName)
    }

    it("should get jobs by binary name") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
        binJar.uploadTime, binJar.binaryStorageId.get), timeout)

      Await.result(dao.saveJob(normalJob), timeout)

      Await.result(dao.getJobsByBinaryName(binJar.appName), timeout) should be(Seq(normalJob))
    }

    it("should get jobs by binary name and status") {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
        binJar.uploadTime, binJar.binaryStorageId.get), timeout)

      val runningJob = normalJob.copy(state = JobStatus.Running)
      val restartingJob = normalJob.copy(jobId = "restarting", state = JobStatus.Restarting)
      val errorJob = normalJob.copy(jobId = "error", state = JobStatus.Error)

      Await.result(dao.saveJob(runningJob), timeout)
      Await.result(dao.saveJob(restartingJob), timeout)
      Await.result(dao.saveJob(errorJob), timeout)

      Await.result(dao.getJobsByBinaryName(binJar.appName,
        Some(Seq(JobStatus.Running, JobStatus.Error))), timeout) should contain allOf(runningJob, errorJob)
    }
  }

  /*
   * Tests: Contexts
   */

  describe("Context tests") {

    it("should save and retrieve contexts") {
      Await.result(dao.saveContext(normalContext), timeout) should equal(true)
      Await.result(dao.getContext(normalContext.id), timeout) should equal(Some(normalContext))
    }

    it("should return None if there is no context with a given id") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.getContext("someOtherContextId"), timeout) should equal(None)
    }

    it("should update a context if saved with the same id") {
      Await.result(dao.saveContext(sameIdContext), timeout) should equal(true)
      Await.result(dao.saveContext(normalContext), timeout) should equal(true)
      Await.result(dao.getContext(normalContext.id), timeout) should equal(Some(normalContext))

    }
    it("should retrieve contexts by their name") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.saveContext(minimalContext), timeout)
      Await.result(dao.getContextByName("someName"), timeout) should equal(Some(normalContext))
      Await.result(dao.getContextByName("someNonExistingName"), timeout) should equal(None)
    }

    it("should retrieve all contexts") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.saveContext(sameIdContext), timeout)
      Await.result(dao.saveContext(minimalContext), timeout)
      val jobs = Await.result(dao.getContexts(None, None), timeout)
      jobs.size should equal(2)
      jobs should equal(Seq(sameIdContext, minimalContext))
      Await.result(dao.getContexts(Some(100), None), timeout) should equal(jobs)
    }

    it("should retrieve all contexts with a limit") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.saveContext(sameIdContext), timeout)
      Await.result(dao.saveContext(minimalContext), timeout)
      Await.result(dao.getContexts(Some(1), None), timeout) should equal(Seq(sameIdContext))
    }

    it("should retrieve all contexts filtered by a state") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.saveContext(sameIdContext), timeout)
      Await.result(dao.saveContext(minimalContext), timeout)
      Await.result(dao.saveContext(anotherStateContext), timeout)
      val jobs = Await.result(dao.getContexts(None, Some(Seq("someState", "nonexistingState"))),
          timeout)
      jobs should equal(Seq(anotherStateContext, minimalContext))
    }

    it("should retrieve all contexts limited and filtered by a state") {
      Await.result(dao.saveContext(normalContext), timeout)
      Await.result(dao.saveContext(sameIdContext), timeout)
      Await.result(dao.saveContext(minimalContext), timeout)
      Await.result(dao.saveContext(anotherStateContext), timeout)
      val jobs = Await.result(dao.getContexts(Some(1), Some(Seq("someState", "nonexistingState"))),
          timeout)
      jobs should equal(Seq(anotherStateContext))
    }

    it("should save context with exception (custom contexts exceptions checked)") {
      val contextResolutionFailedOnStopContextException = normalContext.copy(
        error = Some(ResolutionFailedOnStopContextException(normalContext)))
      Await.result(dao.saveContext(contextResolutionFailedOnStopContextException), timeout)
      Await.result(dao.getContext(contextResolutionFailedOnStopContextException.id), timeout).get should
        equal(contextResolutionFailedOnStopContextException)

      val contextContextReconnectFailedException = normalContext.copy(
        error = Some(ContextReconnectFailedException()))
      Await.result(dao.saveContext(contextContextReconnectFailedException), timeout)
      Await.result(dao.getContext(contextContextReconnectFailedException.id), timeout).get should
        equal(contextContextReconnectFailedException)

      val contextContextJVMInitializationTimeout = normalContext.copy(
        error = Some(ContextJVMInitializationTimeout()))
      Await.result(dao.saveContext(contextContextJVMInitializationTimeout), timeout)
      Await.result(dao.getContext(contextContextJVMInitializationTimeout.id), timeout).get should
        equal(contextContextJVMInitializationTimeout)
    }

    it("should delete contexts in final state older than a certain date") {
      // Persist three contexts
      val cutoffDate = toDateTime(724291200L)
      val oldContext = normalContext.copy(id = "1", state = ContextStatus.Finished,
        endTime = Some(cutoffDate.minusHours(1)))
      val recentContext = normalContext.copy(id = "2", state = ContextStatus.Finished,
        endTime = Some(cutoffDate.plusHours(1)))
      val runningContext = normalContext.copy(id = "3", state = ContextStatus.Running,
        endTime = None)
      val restartingContext = normalContext.copy(id = "4", state = ContextStatus.Restarting,
        endTime = Some(cutoffDate.minusHours(1)))
      Await.result(dao.saveContext(oldContext), timeout)
      Await.result(dao.saveContext(recentContext), timeout)
      Await.result(dao.saveContext(runningContext), timeout)
      Await.result(dao.saveContext(restartingContext), timeout)
      Await.result(dao.getContexts(None, None), timeout).size should equal(4)
      // Delete (one) old one
      val result = Await.result(dao.deleteFinalContextsOlderThan(cutoffDate), timeout)
      result should equal(true)
      // Assert that (only) old one is gone
      Await.result(dao.getContexts(None, None), timeout) should
        equal(Seq(recentContext, runningContext, restartingContext))
      Await.result(dao.getContext(oldContext.id), timeout) should equal(None)
      Await.result(dao.getContext(recentContext.id), timeout) should equal(Some(recentContext))
      Await.result(dao.getContext(runningContext.id), timeout) should equal(Some(runningContext))
      Await.result(dao.getContext(restartingContext.id), timeout) should equal(Some(restartingContext))
    }

  }

  /*
   * Tests: Jobs
   */

  describe("Job tests") {

    def insertInitialBinary() : Unit = { Await.result(dao.saveBinary(binJar.appName, binJar.binaryType,
        binJar.uploadTime, binJar.binaryStorageId.get), timeout) }

    it("should save and retrieve job info properly") {
      insertInitialBinary()
      val success = Await.result(dao.saveJob(normalJob), timeout)
      success should equal(true)
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(Some(normalJob))
    }

    it("should return None if there is no job with a given id") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.getJob("someOtherJobId"), timeout) should equal(None)
    }

    it("should update a job if saved with the same id") {
      insertInitialBinary()
      var success = Await.result(dao.saveJob(sameIdJob), timeout)
      success should equal(true)
      success = Await.result(dao.saveJob(normalJob), timeout)
      success should equal(true)
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(Some(normalJob))
    }

    it("should retrieve jobs by their context id") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJob(minimalJob), timeout)
      Await.result(dao.saveJob(anotherJob), timeout)
      Await.result(dao.saveJob(multiJarJob), timeout)
      val jobs = Await.result(dao.getJobsByContextId("someContextId", None), timeout)
      jobs.size should equal(2)
      jobs should equal(Seq(minimalJob, normalJob)) //right order?
    }

    it("should retrieve jobs by their context id filtered by states") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJob(sameIdJob), timeout)
      Await.result(dao.saveJob(minimalJob), timeout)
      Await.result(dao.saveJob(anotherJob), timeout)
      Await.result(dao.saveJob(multiJarJob), timeout)
      val jobs = Await.result(dao.getJobsByContextId(sameIdJob.contextId, Some(Seq(sameIdJob.state))),
          timeout)
      jobs should equal(Seq(sameIdJob))
    }

    it("should retrieve all jobs") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJob(minimalJob), timeout)
      Await.result(dao.saveJob(anotherJob), timeout)
      Await.result(dao.saveJob(multiJarJob), timeout)
      val jobs = Await.result(dao.getJobs(100, None), timeout)
      jobs.size should equal(4)
    }

    it("should retrieve all jobs with a limit") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJob(minimalJob), timeout)
      Await.result(dao.saveJob(anotherJob), timeout)
      val jobs = Await.result(dao.getJobs(1, None), timeout)
      jobs should equal(Seq(minimalJob))
    }

    it("should retrieve all jobs with a limit filtered by a state") {
      insertInitialBinary()
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJob(minimalJob), timeout)
      Await.result(dao.saveJob(anotherJob), timeout)
      Await.result(dao.saveJob(multiJarJob), timeout)
      val jobs = Await.result(dao.getJobs(1, Some(minimalJob.state)), timeout)
      jobs should equal(Seq(minimalJob))
    }

    it("should retrieve jobs with missing binaries") {
      val success = Await.result(dao.saveJob(normalJob), timeout)
      success should equal(true)
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(Some(normalJob))
      Await.result(dao.getJobs(100, None), timeout).size should equal(1)
    }

    it("should retrieve jobs with multiple binaries") {
      val success = Await.result(dao.saveJob(multiJarJob), timeout)
      success should equal(true)
      Await.result(dao.getJob(multiJarJob.jobId), timeout) should equal(Some(multiJarJob))
    }

    it("should delete jobs in final state older than a certain date") {
      // Persist a few jobs
      val cutoffDate = toDateTime(724291200L)
      val oldJob = normalJob.copy(jobId = "1", state = JobStatus.Finished,
        endTime = Some(cutoffDate.minusHours(1)))
      val recentJob = normalJob.copy(jobId = "2", state = JobStatus.Finished,
        endTime = Some(cutoffDate.plusHours(1)))
      val runningJob = normalJob.copy(jobId = "3", state = JobStatus.Running,
        endTime = None)
      val restartingJob = normalJob.copy(jobId = "4", state = JobStatus.Restarting,
        endTime = Some(cutoffDate.minusHours(1)))
      Await.result(dao.saveJob(oldJob), timeout)
      Await.result(dao.saveJob(recentJob), timeout)
      Await.result(dao.saveJob(runningJob), timeout)
      Await.result(dao.saveJob(restartingJob), timeout)
      Await.result(dao.getJobs(100, None), timeout).size should equal(4)
      // Query old ones
      val result = Await.result(dao.getFinalJobsOlderThan(cutoffDate), timeout)
      result should equal(Seq(oldJob))
    }

    it("should delete a list of jobs"){
      // Persist two jobs
      val job1 = normalJob.copy(jobId = "1")
      val job2 = normalJob.copy(jobId = "2")
      Await.result(dao.saveJob(job1), timeout)
      Await.result(dao.saveJob(job2), timeout)
      // Delete one
      Await.result(dao.deleteJobs(Seq(job1.jobId)), timeout) should equal (true)
      // Assert that (only) one is gone
      Await.result(dao.getJobs(100, None), timeout).toSet should equal(
        Set(job2))
      Await.result(dao.getJob(job1.jobId), timeout) should equal(None)
      Await.result(dao.getJob(job2.jobId), timeout) should equal(Some(job2))
    }

    it("should delete job configs with jobs"){
      val cutoffDate = toDateTime(724291200L)
      // Persist job with config
      Await.result(dao.saveJob(normalJob), timeout)
      Await.result(dao.saveJobConfig(normalJob.jobId, config1), timeout)
      Await.result(dao.getJobConfig(normalJob.jobId), timeout) should equal (Some(config1))
      // Delete
      Await.result(dao.deleteJobs(Seq(normalJob.jobId)), timeout) should equal(true)
      // Assert
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(None)
      Await.result(dao.getJobConfig(normalJob.jobId), timeout) should equal(None)
    }

    it("should be able to access data after DAO recreation") {
      insertInitialBinary()
      // Save a job
      val success = Await.result(dao.saveJob(normalJob), timeout)
      success should equal(true)
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(Some(normalJob))
      // Restart
      dao = null
      dao = new MetaDataZookeeperDAO(config)
      // Still available?
      Await.result(dao.getJob(normalJob.jobId), timeout) should equal(Some(normalJob))
    }

  }

  /*
   * Tests: JobConfigs
   */

  describe("JobConfig tests") {

    def insertInitialBinaryAndJob() : Unit = {
      Await.result(dao.saveBinary(binJar.appName, binJar.binaryType, binJar.uploadTime,
          binJar.binaryStorageId.get), timeout)
      Await.result(dao.saveJob(normalJob), timeout)
    }

    it("should save and read job config") {
      insertInitialBinaryAndJob()
      Await.result(dao.saveJobConfig("someJobId", config1), timeout) should equal(true)
      Await.result(dao.getJobConfig("someJobId"), timeout) should equal(Some(config1))
    }

    it("should return None for a non-existig config") {
      insertInitialBinaryAndJob()
      Await.result(dao.getJobConfig("someJobId"), timeout) should equal(None)
    }

    it("should write a config for non-existing job") {
      Await.result(dao.saveJobConfig("abcde", config1), timeout) should equal(true)
      Await.result(dao.getJobConfig("abcde"), timeout) should equal(Some(config1))
    }

    it("should update configs on a second write call") {
      insertInitialBinaryAndJob()
      Await.result(dao.saveJobConfig("someJobId", config1), timeout) should equal(true)
      Await.result(dao.getJobConfig("someJobId"), timeout) should equal(Some(config1))
      Await.result(dao.saveJobConfig("someJobId", config2), timeout) should equal(true)
      Await.result(dao.getJobConfig("someJobId"), timeout) should equal(Some(config2))
    }

  }

}
