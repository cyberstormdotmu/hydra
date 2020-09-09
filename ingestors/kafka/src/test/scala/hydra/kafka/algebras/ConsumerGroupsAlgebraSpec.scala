package hydra.kafka.algebras

import cats.effect.{Concurrent, ContextShift, IO, Sync, Timer}
import cats.implicits._
import hydra.avro.registry.SchemaRegistry
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.avro.generic.GenericRecord
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterAll}
import retry.RetryPolicies._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ConsumerGroupsAlgebraSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll with EmbeddedKafka {

  private val port = 8092

  implicit private val kafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort = port, zooKeeperPort = 3182)

  implicit private val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val concurrentEffect: Concurrent[IO] = IO.ioConcurrentEffect

  private implicit val policy: RetryPolicy[IO] = limitRetries[IO](5) |+| exponentialBackoff[IO](500.milliseconds)
  private implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  private implicit def noop[A]: (A, RetryDetails) => IO[Unit] = retry.noop[IO, A]

  implicit private def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLogger[F]

  private implicit class RetryAndAssert[A](boolIO: IO[A]) {
    def retryIfFalse(check: A => Boolean): IO[Assertion] =
      boolIO.map(check).retryingM(identity, policy, noop).map(assert(_))
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedKafka.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    EmbeddedKafka.stop()
  }

  private val internalKafkaConsumerTopic = "__consumer_offsets"
  private val summarizedConsumerGroups = "dvs_internal_consumers"
  private val bootstrapServers = s"localhost:$port"
  private val consumerGroup = "consumerGroupName"

  (for {
    kafkaClient <- KafkaClientAlgebra.test[IO]
    schemaRegistry <- SchemaRegistry.test[IO]
    consumerGroupAlgebra <- ConsumerGroupsAlgebra.make(internalKafkaConsumerTopic, summarizedConsumerGroups, bootstrapServers, consumerGroup, consumerGroup, kafkaClient, schemaRegistry)
  } yield {
    runTests(consumerGroupAlgebra, schemaRegistry, kafkaClient)
  }).unsafeRunSync()

  def runTests(cga: ConsumerGroupsAlgebra[IO], schemaRegistry: SchemaRegistry[IO], kafkaClient: KafkaClientAlgebra[IO]): Unit = {
    val topicName = "dvs_internal_test123"
    val (keyGR, valueGR) = getGenericRecords(topicName, "key123", "value123")
    createTopic(topicName, keyGR, valueGR, schemaRegistry)
    kafkaClient.publishMessage((keyGR, Some(valueGR)), topicName).unsafeRunSync()
    kafkaClient.consumeMessages(topicName, "randomConsumerGroup").compile.drain.unsafeRunSync()

    "ConsumerGroupAlgebraSpec" should {

      "consume offsets into the internal topic" in {
        cga.getConsumersForTopic(topicName).unsafeRunSync().consumers should have length 1
      }
    }
  }

  private def createTopic(subject: String, keyGR: GenericRecord, valueGR: GenericRecord, schemaRegistry: SchemaRegistry[IO]): Unit = {
    schemaRegistry.registerSchema(s"$subject-key", keyGR.getSchema).unsafeRunSync()
    schemaRegistry.registerSchema(s"$subject-value", valueGR.getSchema).unsafeRunSync()
  }

  private def getGenericRecords(subject: String, keyValue: String, value: String): (GenericRecord, GenericRecord) = {
    val (_, (_, keyRecord), valueRecord) = KafkaClientAlgebraSpec.topicAndKeyAndValue(subject, keyValue, value)
    (keyRecord, valueRecord)
  }

}
