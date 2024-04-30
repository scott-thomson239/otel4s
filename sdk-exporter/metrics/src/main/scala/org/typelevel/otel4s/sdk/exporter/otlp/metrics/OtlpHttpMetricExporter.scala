/*
 * Copyright 2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.otel4s.sdk.exporter
package otlp
package metrics

import cats.Applicative
import cats.Foldable
import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Console
import fs2.compression.Compression
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.Headers
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.syntax.literals._
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.exporter.AggregationSelector
import org.typelevel.otel4s.sdk.metrics.exporter.AggregationTemporalitySelector
import org.typelevel.otel4s.sdk.metrics.exporter.CardinalityLimitSelector
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

import scala.concurrent.duration._

/** Exports metrics via HTTP. Support `json` and `protobuf` encoding.
  *
  * @see
  *   [[https://opentelemetry.io/docs/specs/otel/protocol/exporter/]]
  *
  * @see
  *   [[https://opentelemetry.io/docs/concepts/sdk-configuration/otlp-exporter-configuration/]]
  */
private final class OtlpHttpMetricExporter[F[_]: Applicative] private[otlp] (
    client: OtlpHttpClient[F, MetricData],
    val aggregationTemporalitySelector: AggregationTemporalitySelector,
    val defaultAggregationSelector: AggregationSelector,
    val defaultCardinalityLimitSelector: CardinalityLimitSelector
) extends MetricExporter.Push[F] {
  val name: String = s"OtlpHttpMetricExporter{client=$client}"

  def exportMetrics[G[_]: Foldable](metrics: G[MetricData]): F[Unit] =
    client.doExport(metrics)

  def flush: F[Unit] = Applicative[F].unit
}

object OtlpHttpMetricExporter {

  private[otlp] object Defaults {
    val Endpoint: Uri = uri"http://localhost:4318/v1/metrics"
    val Timeout: FiniteDuration = 10.seconds
    val GzipCompression: Boolean = false
  }

  /** A builder of [[OtlpHttpMetricExporter]] */
  sealed trait Builder[F[_]] {

    /** Sets the OTLP endpoint to connect to.
      *
      * The endpoint must start with either `http://` or `https://`, and include
      * the full HTTP path.
      *
      * Default value is `http://localhost:4318/v1/metrics`.
      */
    def withEndpoint(endpoint: Uri): Builder[F]

    /** Sets the maximum time to wait for the collector to process an exported
      * batch of spans.
      *
      * Default value is `10 seconds`.
      */
    def withTimeout(timeout: FiniteDuration): Builder[F]

    /** Enables Gzip compression.
      *
      * The compression is disabled by default.
      */
    def withGzip: Builder[F]

    /** Disables Gzip compression. */
    def withoutGzip: Builder[F]

    /** Adds headers to requests. */
    def addHeaders(headers: Headers): Builder[F]

    /** Sets the explicit TLS context the HTTP client should use.
      */
    def withTLSContext(context: TLSContext[F]): Builder[F]

    /** Sets the retry policy to use.
      *
      * Default retry policy is [[RetryPolicy.default]].
      */
    def withRetryPolicy(policy: RetryPolicy): Builder[F]

    /** Configures the exporter to use the given encoding.
      *
      * Default encoding is `Protobuf`.
      */
    def withEncoding(encoding: HttpPayloadEncoding): Builder[F]

    /** Sets the aggregation temporality selector to use.
      *
      * Default selector is
      * [[org.typelevel.otel4s.sdk.metrics.exporter.AggregationTemporalitySelector.alwaysCumulative AggregationTemporalitySelector.alwaysCumulative]].
      */
    def withAggregationTemporalitySelector(
        selector: AggregationTemporalitySelector
    ): Builder[F]

    /** Sets the default aggregation selector to use.
      *
      * If no views are configured for a metric instrument, an aggregation
      * provided by the selector will be used.
      *
      * Default selector is
      * [[org.typelevel.otel4s.sdk.metrics.exporter.AggregationSelector.default AggregationSelector.default]].
      */
    def withDefaultAggregationSelector(
        selector: AggregationSelector
    ): Builder[F]

    /** Sets the default cardinality limit selector to use.
      *
      * If no views are configured for a metric instrument, a limit provided by
      * the selector will be used.
      *
      * Default selector is
      * [[org.typelevel.otel4s.sdk.metrics.exporter.CardinalityLimitSelector.default CardinalityLimitSelector.default]].
      */
    def withDefaultCardinalityLimitSelector(
        selector: CardinalityLimitSelector
    ): Builder[F]

    /** Configures the exporter to use the given client.
      *
      * @note
      *   the 'timeout' and 'tlsContext' settings will be ignored. You must
      *   preconfigure the client manually.
      *
      * @param client
      *   the custom http4s client to use
      */
    def withClient(client: Client[F]): Builder[F]

    /** Creates a [[OtlpHttpMetricExporter]] using the configuration of this
      * builder.
      */
    def build: Resource[F, MetricExporter.Push[F]]
  }

  /** Creates a [[Builder]] of [[OtlpHttpMetricExporter]] with the default
    * configuration:
    *   - encoding: `Protobuf`
    *   - endpoint: `http://localhost:4318/v1/metrics`
    *   - timeout: `10 seconds`
    *   - retry policy: 5 exponential attempts, initial backoff is `1 second`,
    *     max backoff is `5 seconds`
    */
  def builder[F[_]: Async: Network: Compression: Console]: Builder[F] =
    BuilderImpl(
      encoding = HttpPayloadEncoding.Protobuf,
      endpoint = Defaults.Endpoint,
      gzipCompression = Defaults.GzipCompression,
      timeout = Defaults.Timeout,
      headers = Headers.empty,
      retryPolicy = RetryPolicy.default,
      aggregationTemporalitySelector =
        AggregationTemporalitySelector.alwaysCumulative,
      defaultAggregationSelector = AggregationSelector.default,
      defaultCardinalityLimitSelector = CardinalityLimitSelector.default,
      tlsContext = None,
      client = None
    )

  private final case class BuilderImpl[
      F[_]: Async: Network: Compression: Console
  ](
      encoding: HttpPayloadEncoding,
      endpoint: Uri,
      gzipCompression: Boolean,
      timeout: FiniteDuration,
      headers: Headers,
      retryPolicy: RetryPolicy,
      aggregationTemporalitySelector: AggregationTemporalitySelector,
      defaultAggregationSelector: AggregationSelector,
      defaultCardinalityLimitSelector: CardinalityLimitSelector,
      tlsContext: Option[TLSContext[F]],
      client: Option[Client[F]]
  ) extends Builder[F] {

    def withTimeout(timeout: FiniteDuration): Builder[F] =
      copy(timeout = timeout)

    def withEndpoint(endpoint: Uri): Builder[F] =
      copy(endpoint = endpoint)

    def addHeaders(headers: Headers): Builder[F] =
      copy(headers = this.headers ++ headers)

    def withGzip: Builder[F] =
      copy(gzipCompression = true)

    def withoutGzip: Builder[F] =
      copy(gzipCompression = false)

    def withTLSContext(context: TLSContext[F]): Builder[F] =
      copy(tlsContext = Some(context))

    def withRetryPolicy(policy: RetryPolicy): Builder[F] =
      copy(retryPolicy = policy)

    def withEncoding(encoding: HttpPayloadEncoding): Builder[F] =
      copy(encoding = encoding)

    def withAggregationTemporalitySelector(
        selector: AggregationTemporalitySelector
    ): Builder[F] =
      copy(aggregationTemporalitySelector = selector)

    def withDefaultAggregationSelector(
        selector: AggregationSelector
    ): Builder[F] =
      copy(defaultAggregationSelector = selector)

    def withDefaultCardinalityLimitSelector(
        selector: CardinalityLimitSelector
    ): Builder[F] =
      copy(defaultCardinalityLimitSelector = selector)

    def withClient(client: Client[F]): Builder[F] =
      copy(client = Some(client))

    def build: Resource[F, MetricExporter.Push[F]] = {
      import MetricsProtoEncoder.exportMetricsRequest
      import MetricsProtoEncoder.jsonPrinter

      for {
        client <- OtlpHttpClient.create[F, MetricData](
          encoding,
          endpoint,
          timeout,
          headers,
          gzipCompression,
          retryPolicy,
          tlsContext,
          client
        )
      } yield new OtlpHttpMetricExporter[F](
        client,
        aggregationTemporalitySelector,
        defaultAggregationSelector,
        defaultCardinalityLimitSelector
      )
    }
  }

}
