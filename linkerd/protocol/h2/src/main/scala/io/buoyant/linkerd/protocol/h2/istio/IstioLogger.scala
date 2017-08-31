package io.buoyant.linkerd.protocol.h2.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.logging.Logger
import com.twitter.util.Stopwatch
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.{DefaultMixerHost, DefaultMixerPort, IstioLoggerBase, MixerClient}
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.linkerd.protocol.h2.H2LoggerConfig

class IstioLogger(val mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] with IstioLoggerBase {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val istioRequest = H2IstioRequest(req)

    val elapsed = Stopwatch.start()

    svc(req).respond { ret =>

      val duration = elapsed()
      val istioResponse = H2IstioResponse(ret, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}

case class IstioLoggerConfig(
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends H2LoggerConfig {

  @JsonIgnore
  override def role = Stack.Role("IstioLogger")
  @JsonIgnore
  override def description = "Logs telemetry data to Istio Mixer"
  @JsonIgnore
  override def parameters = Seq()

  @JsonIgnore
  private[this] val log = Logger.get("IstioLoggerConfig")

  @JsonIgnore
  private[h2] val host = mixerHost.getOrElse(DefaultMixerHost)
  @JsonIgnore
  private[h2] val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)
  log.info("connecting to Istio Mixer at %s:%d", host, port)

  @JsonIgnore
  private[this] val mixerDst = Name.bound(Address(host, port))

  @JsonIgnore
  private[this] val mixerService = H2.client
    .withParams(H2.client.params)
    .newService(mixerDst, "istioLogger")

  @JsonIgnore
  private[h2] val client = new MixerClient(mixerService)

  @JsonIgnore
  def mk(params: Stack.Params): Filter[Request, Response, Request, Response] = {
    new IstioLogger(client, params)
  }
}

class IstioLoggerInitializer extends LoggerInitializer {
  val configClass = classOf[IstioLoggerConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioLoggerInitializer extends IstioLoggerInitializer
