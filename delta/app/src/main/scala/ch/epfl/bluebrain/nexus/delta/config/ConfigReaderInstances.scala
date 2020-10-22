package ch.epfl.bluebrain.nexus.delta.config

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.service.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.identity.GroupsConfig
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.sourcing.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig._
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import com.typesafe.scalalogging.Logger
import monix.execution.Scheduler
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto._

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * Common ConfigReader instances for types that are not defined in the app module.
  */
trait ConfigReaderInstances {

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] =
    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .map(uri => BaseUri(uri))
    )

  implicit final val aggregateConfigReader: ConfigReader[AggregateConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj                   <- cursor.asObjectCursor
        atc                   <- obj.atKey("ask-timeout")
        askTimeout            <- ConfigReader[FiniteDuration].from(atc)
        emdc                  <- obj.atKey("evaluation-max-duration")
        evaluationMaxDuration <- ConfigReader[FiniteDuration].from(emdc)
        ssc                   <- obj.atKey("stash-size")
        stashSize             <- ssc.asInt
      } yield AggregateConfig(Timeout(askTimeout), evaluationMaxDuration, Scheduler.global, stashSize)
    }

  implicit final val retryStrategyConfigReader: ConfigReader[RetryStrategyConfig] = {
    val onceRetryStrategy: ConfigReader[OnceStrategyConfig]               = deriveReader[OnceStrategyConfig]
    val constantRetryStrategy: ConfigReader[ConstantStrategyConfig]       = deriveReader[ConstantStrategyConfig]
    val exponentialRetryStrategy: ConfigReader[ExponentialStrategyConfig] = deriveReader[ExponentialStrategyConfig]

    ConfigReader.fromCursor { cursor =>
      for {
        obj      <- cursor.asObjectCursor
        rc       <- obj.atKey("retry")
        retry    <- ConfigReader[String].from(rc)
        strategy <- retry match {
                      case "never"       => Right(AlwaysGiveUp)
                      case "once"        => onceRetryStrategy.from(obj)
                      case "constant"    => constantRetryStrategy.from(obj)
                      case "exponential" => exponentialRetryStrategy.from(obj)
                      case other         =>
                        Left(
                          ConfigReaderFailures(
                            ConvertFailure(
                              CannotConvert(
                                other,
                                "string",
                                "'retry' value must be one of ('never', 'once', 'constant', 'exponential')"
                              ),
                              obj
                            )
                          )
                        )
                    }
      } yield strategy
    }
  }

  implicit final val indexingConfigReader: ConfigReader[IndexingConfig] = {
    val logger: Logger = Logger[IndexingConfig]

    @nowarn("cat=unused")
    implicit val retryStrategyConfig: ConfigReader[RetryStrategy] =
      retryStrategyConfigReader.map(config =>
        RetryStrategy(config, _ => false, RetryStrategy.logError(logger, "indexing"))
      )

    deriveReader[IndexingConfig]
  }

  implicit final val groupsConfigReader: ConfigReader[GroupsConfig] =
    deriveReader[GroupsConfig]

  implicit final val keyValueStoreConfigReader: ConfigReader[KeyValueStoreConfig] =
    deriveReader[KeyValueStoreConfig]

  implicit final val paginationConfigReader: ConfigReader[PaginationConfig] =
    deriveReader[PaginationConfig]

  implicit final val realmsConfigReader: ConfigReader[RealmsConfig] =
    deriveReader[RealmsConfig]

}

object ConfigReaderInstances extends ConfigReaderInstances
