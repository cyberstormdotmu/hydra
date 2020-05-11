package hydra.ingest.services

import cats.MonadError
import cats.implicits._
import com.pluralsight.hydra.avro.JsonToAvroConversionException
import hydra.avro.registry.{ConfluentSchemaRegistry, JsonToAvroConversionExceptionWithMetadata, SchemaRegistry}
import hydra.avro.resource.SchemaResource
import hydra.avro.util.{AvroUtils, SchemaWrapper}
import hydra.core.ingest.HydraRequest
import hydra.core.ingest.RequestParams.HYDRA_KAFKA_TOPIC_PARAM
import hydra.core.transport.{AckStrategy, ValidationStrategy}
import hydra.kafka.algebras.KafkaClientAlgebra
import hydra.kafka.producer.AvroRecord
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import scalacache._
import scalacache.guava._
import scalacache.memoization._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

final class IngestionFlow[F[_]: MonadError[*[_], Throwable]: Mode](schemaRegistry: SchemaRegistry[F], kafkaClient: KafkaClientAlgebra[F], schemaRegistryBaseUrl: String) {

  import IngestionFlow._

  implicit val guavaCache: Cache[SchemaWrapper] = GuavaCache[SchemaWrapper]

  private def getValueSchema(topicName: String): F[Schema] = {
    schemaRegistry.getSchemaBySubject(topicName + "-value")
      .flatMap(maybeSchema => MonadError[F, Throwable].fromOption(maybeSchema, new Exception))
  }

  private def getValueSchemaWrapper(topicName: String): F[SchemaWrapper] = memoizeF[F, SchemaWrapper](Some(2.minutes)) {
    getValueSchema(topicName).map { valueSchema =>
      SchemaWrapper.from(valueSchema)
    }
  }

  def ingest(request: HydraRequest): F[Unit] = {
    request.metadataValue(HYDRA_KAFKA_TOPIC_PARAM) match {
      case Some(topic) => getValueSchemaWrapper(topic).flatMap { schemaWrapper =>
        val useStrictValidation = request.validationStrategy == ValidationStrategy.Strict
        val payloadMaybe: Try[Option[GenericRecord]] = Option(request.payload) match {
          case Some(p) => convertToAvro(topic, schemaWrapper, useStrictValidation, p).map(avroRecord => Some(avroRecord.payload))
          case None => Success(None)
        }
        // TODO: Support v2
        val key = schemaWrapper.primaryKeys.toList match {
          case Nil => None
          case l =>
            val a = l.map(pkName => payloadMaybe.map(_.map(p => Try(p.get(pkName)))))
            a
//            .mkString("|").some
        }
        kafkaClient.publishStringKeyMessage((key, payloadMaybe), topic)
      }.void
      case None => MonadError[F, Throwable].raiseError(MissingTopicNameException(request))
    }
  }

  private def convertToAvro(topic: String, schemaWrapper: SchemaWrapper, useStrictValidation: Boolean, payloadString: String): Try[AvroRecord] = {
    Try(AvroRecord(topic, schemaWrapper.schema, None, payloadString, AckStrategy.Replicated, useStrictValidation)).recoverWith {
      case e: JsonToAvroConversionException =>
        val location = s"$schemaRegistryBaseUrl/subjects/$topic-value/versions/latest/schema"
        Failure(new RuntimeException(s"${e.getMessage} [$location]"))
      case e => Failure(e)
    }
  }
}

object IngestionFlow {
  final case class MissingTopicNameException(request: HydraRequest)
    extends Exception(s"Missing the topic name in request with correlationId ${request.correlationId}")
}
