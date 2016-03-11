/*
 * Copyright 2016 HM Revenue & Customs
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

package controllers

import akka.actor._
import auth.RouterAuthenticationProvider
import config.FrontendGlobal._
import config.{FrontendAppConfig, FrontendAuditConnector}
import connector.FrontendAuthConnector
import controllers.AuditActor.AuditMessage
import controllers.AuditSupervisor.CreateActor
import engine.{Condition, RuleEngine}
import model.Locations._
import model.RoutingReason.RoutingReason
import model._
import play.api.Logger
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import services._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import uk.gov.hmrc.play.config.AppName
import uk.gov.hmrc.play.frontend.auth._
import uk.gov.hmrc.play.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

object RouterController extends RouterController {
  override protected def authConnector = FrontendAuthConnector

  override val defaultLocation = BusinessTaxAccount

  override val metricsMonitoringService = MetricsMonitoringService

  override val ruleEngine = TarRules

  override def throttlingService = ThrottlingService

  override def twoStepVerification = TwoStepVerification

  override def auditConnector = FrontendAuditConnector

  override def createAuditContext() = AuditContext()
}

trait RouterController extends FrontendController with Actions {

  val metricsMonitoringService: MetricsMonitoringService

  def defaultLocation: Location

  def ruleEngine: RuleEngine

  def throttlingService: ThrottlingService

  def twoStepVerification: TwoStepVerification

  def auditConnector: AuditConnector

  def createAuditContext(): TAuditContext

  val account = AuthenticatedBy(authenticationProvider = RouterAuthenticationProvider, pageVisibility = AllowAll).async { implicit user => request => route(user, request) }

  def route(implicit authContext: AuthContext, request: Request[AnyContent]): Future[Result] = {

    val ruleContext = RuleContext(authContext)

    val auditContext = createAuditContext()

    val ruleEngineResult = ruleEngine.getLocation(authContext, ruleContext, auditContext).map(nextLocation => nextLocation.getOrElse(defaultLocation))

    for {
      destinationAfterRulesApplied <- ruleEngineResult
      destinationAfterThrottleApplied <- throttlingService.throttle(destinationAfterRulesApplied, auditContext)
      finalDestination <- twoStepVerification.getDestinationVia2SV(destinationAfterThrottleApplied, ruleContext, auditContext).map(_.getOrElse(destinationAfterThrottleApplied))
    } yield {
      sendAuditEvent(auditContext, destinationAfterThrottleApplied)
      metricsMonitoringService.sendMonitoringEvents(auditContext, destinationAfterThrottleApplied)
      Logger.debug(s"routing to: ${finalDestination.name}")
      sendGAEventAndRedirect(auditContext, finalDestination)
    }
  }

  private def sendGAEventAndRedirect(auditContext: TAuditContext, finalDestination: Location) = {
    Ok(views.html.uplift(finalDestination, auditContext.ruleApplied, FrontendAppConfig.analyticsToken))
  }

  private def sendAuditEvent(auditContext: TAuditContext, throttledLocation: Location)(implicit authContext: AuthContext, request: Request[AnyContent], hc: HeaderCarrier) = {
    auditContext.toAuditEvent(throttledLocation).foreach { auditEvent =>
      auditConnector.sendEvent(auditEvent)
      Logger.debug(s"Routing decision summary: ${auditEvent.detail \ "reasons"}")
    }
  }
}

object TarRules extends RuleEngine {

  import Condition._

  override lazy val auditActor = AuditActor.select

  override val rules = List(
    when(LoggedInViaVerify) thenGoTo PersonalTaxAccount withName "pta-home-page-for-verify-user",

    when(LoggedInViaGovernmentGateway and not(GGEnrolmentsAvailable)) thenGoTo BusinessTaxAccount withName "bta-home-page-gg-unavailable",

    when(LoggedInViaGovernmentGateway and HasAnyBusinessEnrolment) thenGoTo BusinessTaxAccount withName "bta-home-page-for-user-with-business-enrolments",

    when(LoggedInViaGovernmentGateway and HasSelfAssessmentEnrolments and not(SAReturnAvailable)) thenGoTo BusinessTaxAccount withName "bta-home-page-sa-unavailable",

    when(LoggedInViaGovernmentGateway and HasSelfAssessmentEnrolments and not(HasPreviousReturns)) thenGoTo BusinessTaxAccount withName "bta-home-page-for-user-with-no-previous-return",

    when(LoggedInViaGovernmentGateway and HasSelfAssessmentEnrolments and (IsInAPartnership or IsSelfEmployed)) thenGoTo BusinessTaxAccount withName "bta-home-page-for-user-with-partnership-or-self-employment",

    when(LoggedInViaGovernmentGateway and HasSelfAssessmentEnrolments and not(IsInAPartnership) and not(IsSelfEmployed) and not(HasNino)) thenGoTo BusinessTaxAccount withName "bta-home-page-for-user-with-no-partnership-and-no-self-employment-and-no-nino",

    when(LoggedInViaGovernmentGateway and HasSelfAssessmentEnrolments and not(IsInAPartnership) and not(IsSelfEmployed)) thenGoTo PersonalTaxAccount withName "pta-home-page-for-user-with-no-partnership-and-no-self-employment",

    when(AnyOtherRuleApplied) thenGoTo BusinessTaxAccount withName "bta-home-page-passed-through"
  )
}

class AuditSupervisor extends Actor {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case e: Exception => Resume
    }

  def receive = {
    case p: CreateActor => sender() ! context.actorOf(p.props, p.name)
  }
}

object AuditSupervisor {

  case class CreateActor(props: Props, name: String)

}

trait AuditActor extends Actor {

  import AuditActor._

  private var routingReasons = Map.empty[String, Map[String, String]]
  private var throttlingDetails = Map.empty[String, Map[String, String]]
  private var sentTo2SVRegister = Map.empty[String, Boolean]
  private var ruleApplied = Map.empty[String, String]

  private lazy val transactionNames = Map(
    Locations.PersonalTaxAccount -> "sent to personal tax account",
    Locations.BusinessTaxAccount -> "sent to business tax account"
  )

  def auditConnector = FrontendAuditConnector

  implicit class BooleanToString(value: Boolean) {
    def asString: String = if (value) "true" else "false"
  }

  override def receive = {
    case SetRoutingReason(id, auditEventType, result) =>
      val current = routingReasons.getOrElse(id, Map.empty[String, String])
      val updated = current + (auditEventType.key -> result.toString)
      routingReasons = routingReasons + (id -> updated)
      sender() ! Done

    case SetThrottlingDetails(id, throttlingAuditContext) =>
      val current = throttlingDetails.getOrElse(id, Map.empty[String, String])
      val details = Map[String, String](
        "enabled" -> throttlingAuditContext.throttlingEnabled.asString,
        "sticky-routing-applied" -> throttlingAuditContext.stickyRoutingApplied.asString,
        "percentage" -> throttlingAuditContext.throttlingPercentage.getOrElse("-").toString,
        "throttled" -> throttlingAuditContext.throttled.asString,
        "destination-url-before-throttling" -> throttlingAuditContext.initialDestination.url,
        "destination-name-before-throttling" -> throttlingAuditContext.initialDestination.name
      )
      val updated = current ++ details
      throttlingDetails = throttlingDetails + (id -> updated)
      sender() ! Done

    case SetSentTo2SVRegister(id) =>
      sentTo2SVRegister = sentTo2SVRegister + (id -> true)
      sender() ! Done

    case SetRuleApplied(id, rule) =>
      ruleApplied = ruleApplied + (id -> rule)
      sender() ! Done
      PoisonPill

    case GetReasons(id) => sender() ! routingReasons.getOrElse(id, Map.empty[String, String])

    case GetThrottlingDetails(id) => sender() ! throttlingDetails.getOrElse(id, Map.empty[String, String])

    case SendAuditEvent(id, finalLocation, authContext, hc, requestPath) =>
      implicit val headerCarrier = hc
      implicit val ec: ExecutionContext = uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
      val auditEvent = toAuditEvent(finalLocation, authContext, hc, requestPath)
      auditConnector.sendEvent(auditEvent)
      Logger.debug(s"Routing decision summary: ${auditEvent.detail \ "reasons"}")

      routingReasons = routingReasons - id
      throttlingDetails = throttlingDetails - id
      sentTo2SVRegister = sentTo2SVRegister - id
      ruleApplied = ruleApplied - id

      sender() ! Done
  }

  private def toAuditEvent(location: Location, authContext: AuthContext, hc: HeaderCarrier, requestPath: String): ExtendedDataEvent = {
    import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier

    val accounts = authContext.principal.accounts
    val accountMap = accounts.toMap
    val accountsAsJson: Seq[(String, JsValueWrapper)] = accountMap
      .map { case (k, v) => (k, Json.toJsFieldJsValueWrapper(v.toString)) }
      .toSeq
    val optionalAccounts: JsObject = Json.obj(accountsAsJson: _*)
    ExtendedDataEvent(
      auditSource = AppName.appName,
      auditType = "Routing",
      tags = hc.toAuditTags(transactionNames.getOrElse(location, "unknown transaction"), requestPath),
      detail = Json.obj(
        "authId" -> authContext.user.userId,
        "destination" -> location.url,
        "reasons" -> routingReasons,
        "throttling" -> throttlingDetails,
        "ruleApplied" -> ruleApplied
      ) ++ optionalAccounts
    )
  }
}

trait Auditing {

  import akka.pattern._
  import config.FrontendGlobal.timeout

  def withAuditing[T](actorRef: Future[ActorRef], auditMessage: AuditMessage)(block: => T)(implicit ec: ExecutionContext): Future[T] = actorRef.flatMap { actor =>
    val auditDone = actor ? auditMessage
    auditDone.map(_ => block)
  }
}

object AuditActor {

  sealed trait AuditMessage {
    def id: String
  }

  case object Done

  case class SetRoutingReason(override val id: String, auditEventType: RoutingReason, result: Boolean) extends AuditMessage

  case class SetThrottlingDetails(override val id: String, throttlingAuditContext: ThrottlingAuditContext) extends AuditMessage

  case class SetSentTo2SVRegister(override val id: String) extends AuditMessage

  case class SetRuleApplied(override val id: String, rule: String) extends AuditMessage

  case class GetReasons(override val id: String) extends AuditMessage

  case class GetThrottlingDetails(override val id: String) extends AuditMessage

  case class SendAuditEvent(override val id: String, finalLocation: Location, authContext: AuthContext, hc: HeaderCarrier, requestPath: String) extends AuditMessage

  def select: Future[ActorRef] = actorSystem.actorSelection("/user/supervisor/auditor").resolveOne()(5 seconds)
}