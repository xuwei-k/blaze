package blaze.util

import blaze.channel.nio2.ClientChannelFactory
import blaze.pipeline.{Command, LeafBuilder}
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.net.InetSocketAddress
import blaze.pipeline.stages.http.{SimpleHttpResponse, HttpClientStage, Response}

/**
 * @author Bryce Anderson
 *         Created on 2/6/14
 */
object HttpClient {


  private lazy val connManager = new ClientChannelFactory()

  // TODO: the robustness of this method to varying input is highly questionable
  private def parseURL(url: String): (String, Int, String, String) = {
    val uri = java.net.URI.create(if (url.startsWith("http")) url else "http://" + url)

    val port = if (uri.getPort > 0) uri.getPort else (if (uri.getScheme == "http") 80 else 443)

    (uri.getHost, port, uri.getScheme, uri.getPath)
  }

  private def runReq(method: String,
                     url: String,
                     headers: Seq[(String, String)],
                     body: ByteBuffer,
                     timeout: Duration)(implicit ec: ExecutionContext): Future[Response] = {

    val (host, port, _, uri) = parseURL(url)

    val fhead = connManager.connect(new InetSocketAddress(host, port))

    fhead.flatMap { head =>
      val t = new HttpClientStage()
      LeafBuilder(t).base(head)
      head.sendInboundCommand(Command.Connect)
      val f = t.makeRequest(method, host, uri, headers, body)
      // Shutdown our connection
      f.onComplete( _ => t.sendOutboundCommand(Command.Disconnect))

      f
    }
  }

  def GET(url: String, headers: Seq[(String, String)] = Nil, timeout: Duration = Duration.Inf)
         (implicit ec: ExecutionContext = Execution.trampoline): Future[SimpleHttpResponse] = {
    val r = runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)
    r.flatMap {
      case r: SimpleHttpResponse => Future.successful(r)
      case r => Future.failed(new Exception(s"Received invalid response type: ${r.getClass}"))
    }(Execution.directec)
  }
}
