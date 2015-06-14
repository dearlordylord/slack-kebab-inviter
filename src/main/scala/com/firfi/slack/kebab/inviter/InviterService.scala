package com.firfi.slack.kebab.inviter

import akka.actor.ActorSystem
import akka.event.{ LoggingAdapter, Logging }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest, FormData }
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorFlowMaterializer, FlowMaterializer }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.IOException
import scala.concurrent.{ ExecutionContextExecutor, Future }
import spray.json.DefaultJsonProtocol

case class InviteRequest(email: String)

case class InviteResponse(message: String)

trait Protocols extends DefaultJsonProtocol {
  implicit val inviteRequestFormat = jsonFormat1(InviteRequest.apply)
  implicit val inviteResponseFormat = jsonFormat1(InviteResponse.apply)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: FlowMaterializer

  def config: Config
  val logger: LoggingAdapter

  lazy val slackConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnectionTls(config.getString("services.slackHost"), config.getInt("services.slackPort"))

  def slackRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(slackConnectionFlow).runWith(Sink.head)

  def invite(email: String): Future[Either[String, String]] = {
    case class SlackInviteResponse(ok: Boolean, error: Option[String])
    implicit val slackInviteResponseFormat = jsonFormat2(SlackInviteResponse.apply)
    slackRequest(RequestBuilding.Post(s"/api/users.admin.invite",
      FormData("email" -> email, "set_active" -> "true", "token" -> config.getString("services.slackToken")))).flatMap { response =>
      logger.warning(response.toString)
      response.status match {
        case OK =>
          Unmarshal(response.entity).to[SlackInviteResponse].flatMap {
            case (SlackInviteResponse(true, None))         => Future.successful(Right("ok"))
            case (SlackInviteResponse(false, Some(error))) => Future.successful(Left(error))
            case _ =>
              logger.error("Unknown error") // TODO
              Future.failed(new IOException("Unknown error"))
          }
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"Slack request failed with status code ${response.status} and entity $entity"
          logger.error(error)
          Future.failed(new IOException(error))
        }
      }
    }
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      pathPrefix("slack" / "invite") {
        (post & entity(as[InviteRequest])) { inviteRequest =>
          complete {
            val inviteFuture = invite(inviteRequest.email)
            inviteFuture.map[ToResponseMarshallable] {
              case (Right(_))           => InviteResponse("ok")
              case (Left(errorMessage)) => BadRequest -> errorMessage
            }
          }
        }
      }
    }
  }
}

object InviterService extends App with Service {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorFlowMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
