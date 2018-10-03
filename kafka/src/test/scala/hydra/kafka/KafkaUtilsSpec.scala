package hydra.kafka

import java.util.concurrent.ExecutionException

import com.typesafe.config.ConfigFactory
import hydra.kafka.util.KafkaUtils
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.common.requests.CreateTopicsRequest.TopicDetails
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.collection.JavaConverters._

/**
  * Created by alexsilva on 5/17/17.
  */
class KafkaUtilsSpec extends WordSpec
  with BeforeAndAfterAll
  with Matchers
  with Eventually
  with EmbeddedKafka
  with ScalaFutures {

  implicit val config = EmbeddedKafkaConfig(kafkaPort = 8092, zooKeeperPort = 3181)

  val defaultCfg = Map(
    "key.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
    "auto.offset.reset" -> "latest",
    "group.id" -> "hydra",
    "bootstrap.servers" -> "localhost:8092",
    "enable.auto.commit" -> "false",
    "value.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
    "zookeeper.connect" -> "localhost:3181",
    "client.id" -> "string",
    "metadata.fetch.timeout.ms" -> "100000")

  val ku = new KafkaUtils(defaultCfg)

  override def beforeAll = {
    EmbeddedKafka.start()
    val dt = new TopicDetails(1, 1: Short)
    ku.createTopic("test-kafka-utils", dt, 10)
  }

  override def afterAll = EmbeddedKafka.stop()

  val cfg = ConfigFactory.parseString(
    """
      |akka {
      |  kafka.producer {
      |    parallelism = 100
      |    close-timeout = 60s
      |    use-dispatcher = test
      |    kafka-clients {
      |       linger.ms = 10
      |    }
      |  }
      |}
      |hydra_kafka {
      |   schema.registry.url = "localhost:808"
      |   kafka.producer {
      |     bootstrap.servers="localhost:8092"
      |     key.serializer = org.apache.kafka.common.serialization.StringSerializer
      |   }
      |   kafka.clients {
      |      test.producer {
      |        value.serializer = org.apache.kafka.common.serialization.StringSerializer
      |      }
      |      test1.producer {
      |        value.serializer = org.apache.kafka.common.serialization.Tester
      |      }
      |   }
      |}
      |
      """.stripMargin)

  "Kafka Utils" should {
    "return false for a topic that doesn't exist" in {
      val exists = ku.topicExists("unknown").get
      assert(!exists)
    }

    "return true for a topic that exists" in {
      assert(ku.topicExists("test-kafka-utils").map(_ == true).get)
    }

    "return a list of topics" in {
      ku.topicNames().get.indexOf("test-kafka-utils") should be > -1
    }

    "loads default consumer" in {
      val d = KafkaUtils.consumerForClientId("string")
      val props = Map(
        "key.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
        "auto.offset.reset" -> "latest",
        "group.id" -> "hydra",
        "bootstrap.servers" -> "localhost:8092",
        "enable.auto.commit" -> "false",
        "value.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
        "zookeeper.connect" -> "localhost:3181",
        "client.id" -> "string",
        "metadata.fetch.timeout.ms" -> "100000")

      d.get.properties shouldBe props
    }

    "has settings for consumers by client id" in {
      val d = KafkaUtils.loadConsumerSettings("avro", "hydrag")
      val props = Map(
        "key.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
        "auto.offset.reset" -> "latest",
        "group.id" -> "hydrag",
        "bootstrap.servers" -> "localhost:8092",
        "enable.auto.commit" -> "false",
        "value.deserializer" -> "io.confluent.kafka.serializers.KafkaAvroDeserializer",
        "zookeeper.connect" -> "localhost:3181",
        "client.id" -> "avro",
        "metadata.fetch.timeout.ms" -> "100000",
        "schema.registry.url" -> "mock")

      d.properties shouldBe props
    }

    "create ProducerSettings from config" in {

      val settings = KafkaUtils.producerSettings("test", cfg)

      settings.properties shouldBe Map(
        "value.serializer" -> "org.apache.kafka.common.serialization.StringSerializer",
        "key.serializer" -> "org.apache.kafka.common.serialization.StringSerializer",
        "bootstrap.servers" -> "localhost:8092",
        "client.id" -> "test",
        "linger.ms" -> "10")
    }

    "retrieve all clients from a config" in {
      val clients = KafkaUtils.producerSettings(cfg)
      clients.keys should contain allOf("test", "test1")
      clients("test1").properties shouldBe Map(
        "value.serializer" -> "org.apache.kafka.common.serialization.Tester",
        "key.serializer" -> "org.apache.kafka.common.serialization.StringSerializer",
        "bootstrap.servers" -> "localhost:8092",
        "client.id" -> "test1",
        "linger.ms" -> "10")
    }

    "create a topic" in {

      val configs = Map(
        "min.insync.replicas" -> "1",
        "cleanup.policy" -> "compact",
        "segment.bytes" -> "1048576"
      )
      val kafkaUtils = new KafkaUtils(defaultCfg)
      kafkaUtils.topicExists("test.Hydra").get shouldBe false
      val details = new TopicDetails(1, 1: Short, configs.asJava)
      whenReady(kafkaUtils.createTopic("test.Hydra", details, 3000)) { response =>
        response.all().get() shouldBe null //the kafka API returns a 'Void'
        kafkaUtils.topicExists("test.Hydra").get shouldBe true
      }
    }


    "throws error if topic exists" in {
      val configs = Map(
        "min.insync.replicas" -> "1",
        "cleanup.policy" -> "compact",
        "segment.bytes" -> "1048576"
      )
      val kafkaUtils = new KafkaUtils(defaultCfg)
      createCustomTopic("hydra.already.Exists")
      kafkaUtils.topicExists("hydra.already.Exists").get shouldBe true
      val details = new TopicDetails(1, 1, configs.asJava)
      whenReady(kafkaUtils.createTopic("hydra.already.Exists", details, 1000).failed) { response =>
        response shouldBe an[IllegalArgumentException]
      }
    }


    "throws error if configs are invalid" in {
      val configs = Map(
        "min.insync.replicas" -> "1",
        "cleanup.policy" -> "under the carpet"
      )
      val kafkaUtils = new KafkaUtils(defaultCfg)
      val details = new TopicDetails(1, 1, configs.asJava)
      whenReady(kafkaUtils.createTopic("InvalidConfig", details, 1000)) { response =>
        intercept[ExecutionException](response.all().get)
      }
    }
  }
}

