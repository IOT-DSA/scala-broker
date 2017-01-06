package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import play.api.mvc.{ Action, Controller }

/**
 * Handles main web requests.
 */
@Singleton
class MainController @Inject() (implicit config: Configuration, actorSystem: ActorSystem,
                                materializer: Materializer) extends Controller {

  def index = Action { implicit request =>
    Ok(views.html.index(config.underlying.root))
  }
}