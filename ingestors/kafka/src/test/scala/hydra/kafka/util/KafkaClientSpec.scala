package hydra.kafka.util

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import cats.effect.{ContextShift, IO}
import cats.implicits._
import hydra.core.protocol.{Ingest, IngestorCompleted}
import hydra.core.transport.AckStrategy
import hydra.kafka.producer.StringRecord
import hydra.kafka.util.KafkaUtils.TopicDetails
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.ExecutionContext

final class KafkaClientSpec extends WordSpec with Matchers with BeforeAndAfterAll with EmbeddedKafka {

  private val port = 8023
  implicit private val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = port, zooKeeperPort = 3027)
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val system: ActorSystem = ActorSystem("kafka-client-spec-system")

  override def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedKafka.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    EmbeddedKafka.stop()
  }

  (for {
    live <- KafkaClient.live[IO](s"localhost:$port", system.actorSelection(TestProbe().ref.path))
    test <- KafkaClient.test[IO]
  } yield {
    runTests(live, isTest = false)
    runTests(test, isTest = true)
  }).unsafeRunSync()

  runLiveOnlyTests()

  private def runTests(kafkaClient: KafkaClient[IO], isTest: Boolean): Unit = {
    (if (isTest) "KafkaClient#test" else "KafkaClient#live") must {
      "create a topic" in {
        val topicName = "Topic1"
        val topicDetails = TopicDetails(3, 1.toShort)
        (kafkaClient.createTopic(topicName, topicDetails) *> kafkaClient.describeTopic(topicName).map {
          case Some(topic) =>
            topic.name shouldBe topicName
            topic.numberPartitions shouldBe topicDetails.numPartitions
          case None => fail("Found None when a Topic was Expected")
        }).unsafeRunSync()
      }

      "list all topics" in {
        kafkaClient.getTopicNames.unsafeRunSync() shouldBe List("Topic1")
      }

    }
  }

  private def runLiveOnlyTests(): Unit = {
    val probe = TestProbe()
    KafkaClient.live[IO](s"test", system.actorSelection(probe.ref.path)).map { kafkaClient =>
      "KafkaClient#live" must {
        "send ingest request to ingestActor" in {
          val record = StringRecord("some_test_topic", "key", "payload", AckStrategy.Replicated)
          (for {
            f <- kafkaClient.publishMessage(record).start
            _ <- IO(probe.expectMsg(Ingest(record, AckStrategy.Replicated)))
            _ <- IO(probe.reply(IngestorCompleted))
            result <- f.join
          } yield result shouldBe Right(())).unsafeRunSync()
        }
      }
    }.unsafeRunSync()
  }

}
