package spark.jobserver

import java.nio.charset.Charset

import scala.util.Try
import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import akka.pattern.ask
import akka.util.Timeout
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}
import java.nio.file.Files

import scala.concurrent.duration._
import spark.jobserver.JobServer.InvalidConfiguration
import spark.jobserver.common.akka
import spark.jobserver.io.{
  JobDAOActor, JobDAO, ContextInfo, ContextStatus, JobInfo, BinaryInfo, BinaryType, JobStatus}

import scala.concurrent.Await
import scala.concurrent.duration.TimeUnit;
import java.util.UUID
import org.joda.time.DateTime
import java.util.concurrent.TimeUnit

object JobServerSpec {
  val system = ActorSystem("test")
}

class JobServerSpec extends TestKit(JobServerSpec.system) with FunSpecLike with Matchers
  with BeforeAndAfterAll {

  import com.typesafe.config._
  import scala.collection.JavaConverters._

  private val configFile = Files.createTempFile("job-server-config", ".conf")

  override def afterAll() {
    akka.AkkaTestUtils.shutdownAndWait(JobServerSpec.system)
    Files.deleteIfExists(configFile)
  }

  def writeConfigFile(configMap: Map[String, Any]): String = {
    val config = ConfigFactory.parseMap(configMap.asJava).withFallback(ConfigFactory.defaultOverrides())
    Files.write(configFile,
      Seq(config.root.render(ConfigRenderOptions.concise)).asJava,
      Charset.forName("UTF-8"))
    configFile.toAbsolutePath.toString
  }

  def makeSupervisorSystem(config: Config): ActorSystem = system
  implicit val timeout: Timeout = 3 seconds

  describe("Fails on invalid configuration") {
    it("requires context-per-jvm in YARN mode") {
      val configFileName = writeConfigFile(Map(
        "spark.master " -> "yarn",
        "spark.jobserver.context-per-jvm " -> false))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

   it("requires akka.remote.netty.tcp.port in supervise mode") {
      val configFileName = writeConfigFile(Map(
        "spark.submit.deployMode" -> "cluster",
        "spark.jobserver.context-per-jvm" -> true,
        "spark.driver.supervise" -> true,
        "akka.remote.netty.tcp.port" -> 0))

      val invalidConfException = intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
      invalidConfException.getMessage should
        be("Supervise mode requires akka.remote.netty.tcp.port to be hardcoded")
    }

    it("requires context-per-jvm in Mesos mode") {
      val configFileName = writeConfigFile(Map(
        "spark.master " -> "mesos://test:123",
        "spark.jobserver.context-per-jvm " -> false))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("requires context-per-jvm in cluster mode") {
      val configFileName = writeConfigFile(Map(
        "spark.submit.deployMode " -> "cluster",
        "spark.jobserver.context-per-jvm " -> false))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("does not support context-per-jvm and JobFileDAO") {
      val configFileName = writeConfigFile(Map(
        "spark.jobserver.context-per-jvm " -> true,
        "spark.jobserver.jobdao" -> "spark.jobserver.io.JobFileDAO"))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("does not support context-per-jvm and H2 in-memory DB") {
      val configFileName = writeConfigFile(Map(
        "spark.jobserver.context-per-jvm " -> true,
        "spark.jobserver.jobdao" -> "spark.jobserver.io.JobSqlDAO",
        "spark.jobserver.sqldao.jdbc.url" -> "jdbc:h2:mem"))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("does not support cluster mode and H2 in-memory DB") {
      val configFileName = writeConfigFile(Map(
        "spark.submit.deployMode" -> "cluster",
        "spark.jobserver.context-per-jvm " -> true,
        "spark.jobserver.jobdao" -> "spark.jobserver.io.JobSqlDAO",
        "spark.jobserver.sqldao.jdbc.url" -> "jdbc:h2:mem"))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("does not support cluster mode and H2 file based DB") {
      val configFileName = writeConfigFile(Map(
        "spark.submit.deployMode" -> "cluster",
        "spark.jobserver.context-per-jvm " -> true,
        "spark.jobserver.jobdao" -> "spark.jobserver.io.JobSqlDAO",
        "spark.jobserver.sqldao.jdbc.url" -> "jdbc:h2:file"))

      intercept[InvalidConfiguration] {
        JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))
      }
    }

    it("starts some actors in local mode") {
      val configFileName = writeConfigFile(Map(
        "spark.master" -> "local[1]",
        "spark.submit.deployMode" -> "client",
        "spark.jobserver.context-per-jvm " -> false,
        "spark.jobserver.sqldao.jdbc.url" -> "jdbc:h2:mem"))

      JobServer.start(Seq(configFileName).toArray, makeSupervisorSystem(_))

      Await.result(system.actorSelection("/user/dao-manager").resolveOne, 2 seconds) shouldBe a[ActorRef]
      Await.result(system.actorSelection("/user/data-manager").resolveOne, 2 seconds) shouldBe a[ActorRef]
      Await.result(system.actorSelection("/user/binary-manager").resolveOne, 2 seconds) shouldBe a[ActorRef]
      Await.result(system.actorSelection("/user/context-supervisor").resolveOne, 2 seconds) shouldBe a[ActorRef]
      Await.result(system.actorSelection("/user/job-info").resolveOne, 2 seconds) shouldBe a[ActorRef]
    }

    def createContext(name: String, status: String, genActor: Boolean): ContextInfo = {
      val uuid = UUID.randomUUID().toString()
      var address = "invalidAddress"
      if(genActor) {
        val actor = system.actorOf(Props.empty, name = AkkaClusterSupervisorActor.MANAGER_ACTOR_PREFIX + uuid)
        address = actor.path.address.toString
      }
      return ContextInfo(uuid, name, "", Some(address), DateTime.now(), None, status, None)
    }

    it("reconnect to actors after restart") {
      val daoActor = system.actorOf(JobDAOActor.props(new InMemoryDAO))

      // Add two contexts in Running mode into db, but only initialize one actor
      val ctxRunning = createContext("ctxRunning", ContextStatus.Running, true)
      val ctxTerminated = createContext("ctxTerminated", ContextStatus.Running, false)
      daoActor ! JobDAOActor.SaveContextInfo(ctxRunning)
      daoActor ! JobDAOActor.SaveContextInfo(ctxTerminated)

      def genJob(jobId: String, ctx: ContextInfo, status: String) = {
        val dt = DateTime.parse("2013-05-29T00Z")
        JobInfo(jobId, ctx.id, ctx.name, BinaryInfo("demo", BinaryType.Jar, dt), "com.abc.meme",
            status, dt, None, None)
      }

      val jobRunning = genJob("jid1", ctxRunning, JobStatus.Running)
      val jobTerminated = genJob("jid2", ctxTerminated, JobStatus.Running)
      val jobFinsihed = genJob("jid3", ctxTerminated, JobStatus.Finished)
      daoActor ! JobDAOActor.SaveJobInfo(jobRunning)
      daoActor ! JobDAOActor.SaveJobInfo(jobTerminated)
      daoActor ! JobDAOActor.SaveJobInfo(jobFinsihed)

      JobServer.updateContextStatus(system, daoActor)

      Thread.sleep(2000)

      val timeout = Timeout.apply(Duration.create(3, TimeUnit.SECONDS))
      val resp = Await.result((daoActor ? JobDAOActor.GetContextInfos(None, None))(timeout).
          mapTo[JobDAOActor.ContextInfos], timeout.duration)
      val jobInfos = Await.result((daoActor ? JobDAOActor.GetJobInfos(100))(timeout).
          mapTo[JobDAOActor.JobInfos], timeout.duration)
      // Expect that only the context with the initialized actor is in the running state
      resp.contextInfos.size should equal (2)
      resp.contextInfos.foreach(ci => {
        val jobs = jobInfos.jobInfos.filter(j => j.contextId.equals(ci.id));
        if (ctxRunning.name.equals(ci.name)) {
          ci.state should equal (ContextStatus.Running)
          jobs.head.state should equal (JobStatus.Running)
          jobs.head.error should be (None)
        } else {
          ci.state should equal (ContextStatus.Error)
          ci.error.get.getMessage should be ("Reconnect failed after Jobserver restart")
          var job = jobs.find(j => j.jobId.equals("jid2")).head;
          job.state should equal (JobStatus.Error)
          job.error.get.message should be ("Reconnect failed after Jobserver restart")
          job = jobs.find(j => j.jobId.equals("jid3")).head;
          job should equal (jobFinsihed)
        }
      })
    }
  }
}
