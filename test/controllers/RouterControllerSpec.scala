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

import engine.{Condition, RuleEngine, When}
import model.Locations._
import model.{Location, _}
import org.jsoup.Jsoup
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => eqTo, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeApplication, FakeRequest}
import services._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import uk.gov.hmrc.play.frontend.auth.connectors.domain.Accounts
import uk.gov.hmrc.play.frontend.auth.{AuthContext, LoggedInUser, Principal}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}

import scala.collection.mutable.{Map => mutableMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class RouterControllerSpec extends UnitSpec with MockitoSugar with WithFakeApplication with Eventually {

  val location1 = Location("location1", "/location1")
  val location2 = Location("location2", "/location2")

  private val gaToken = "GA-TOKEN"
  override lazy val fakeApplication = new FakeApplication(additionalConfiguration = Map("google-analytics.token" -> gaToken, "location1.path" -> location1.url))

  case class TestCondition(truth: Boolean) extends Condition {
    override val auditType = None

    override def isTrue(authContext: AuthContext, ruleContext: RuleContext)(implicit request: Request[AnyContent], hc: HeaderCarrier) = Future(truth)
  }

  trait Setup {

    val ruleEngineStubReturningSomeLocation = new RuleEngine {
      override val rules = List(When(TestCondition(true)).thenGoTo(location1))
    }

    val ruleEngineStubReturningNoneLocation = new RuleEngine {
      override val rules = List(When(TestCondition(false)).thenGoTo(location2))
    }

    implicit val fakeRequest = FakeRequest()
    implicit lazy val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

    implicit val authContext = AuthContext(mock[LoggedInUser], Principal(None, Accounts()), None)
    implicit lazy val ruleContext = RuleContext(authContext)
    val auditContext: TAuditContext = AuditContext()
    val mockThrottlingService = mock[ThrottlingService]
    val mockTwoStepVerification = Mocks.mockTwoStepVerification
    val mockMetricsMonitoringService = mock[MetricsMonitoringService]


    def checkResult(route: Future[Result], location: Location, eventName: String) = {
      status(route) shouldBe 200
      val body = contentAsString(route)

      //and
      val page = Jsoup.parse(body)

      page.select("p#non-js-only a").attr("href") shouldBe location.fullUrl

      //and
      body.contains(s"ga('create', '$gaToken', 'auto');") shouldBe true
      body.contains(s"ga('send', 'event', 'routing', '${location.name}', '$eventName', {") shouldBe true
    }
  }

  "router controller" should {

    "return location provided by rules" in new Setup {
      when(mockThrottlingService.throttle(eqTo(location1), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])).thenReturn(location1)

      val controller = new TestRouterController(ruleEngine = ruleEngineStubReturningSomeLocation, throttlingService = mockThrottlingService, twoStepVerification = mockTwoStepVerification)

      //when
      val route = controller.route

      //then
      checkResult(route, location1, "none")

      verify(mockThrottlingService).throttle(eqTo(location1), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])
      verify(mockTwoStepVerification).getDestinationVia2SV(eqTo(location1), eqTo(ruleContext), eqTo(auditContext))(eqTo(authContext), eqTo(fakeRequest), any[HeaderCarrier])
    }

    "return default location when location provided by rules is not defined" in new Setup {
      //given
      val expectedLocation = BusinessTaxAccount

      //and
      when(mockThrottlingService.throttle(eqTo(expectedLocation), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])).thenReturn(expectedLocation)

      val controller = new TestRouterController(defaultLocation = expectedLocation, ruleEngine = ruleEngineStubReturningNoneLocation, throttlingService = mockThrottlingService)

      //when
      val route = controller.route

      //then
      checkResult(route, BusinessTaxAccount, "")

      verify(mockThrottlingService).throttle(eqTo(expectedLocation), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])
    }

    "send monitoring events" in new Setup {

      //given
      val expectedLocation = BusinessTaxAccount

      //and
      when(mockThrottlingService.throttle(eqTo(expectedLocation), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])).thenReturn(expectedLocation)


      val controller = new TestRouterController(defaultLocation = expectedLocation, ruleEngine = ruleEngineStubReturningNoneLocation, throttlingService = mockThrottlingService, metricsMonitoringService = mockMetricsMonitoringService)

      //when
      val route = controller.route

      //then
      checkResult(route, BusinessTaxAccount, "")

      verify(mockThrottlingService).throttle(eqTo(expectedLocation), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])

      verify(mockMetricsMonitoringService).sendMonitoringEvents(eqTo(auditContext), eqTo(expectedLocation))(eqTo(authContext), eqTo(fakeRequest), any[HeaderCarrier])
    }

    "audit the event before redirecting" in new Setup {
      //given
      override val auditContext = mock[TAuditContext]

      val ruleApplied = "rule-applied"
      val mockAuditEvent = mock[ExtendedDataEvent]
      when(mockAuditEvent.detail).thenReturn(Json.parse("{}"))
      when(auditContext.ruleApplied).thenReturn(ruleApplied)

      val auditContextToAuditEventResult = Future(mockAuditEvent)
      when(auditContext.toAuditEvent(any[Location])(any[HeaderCarrier], any[AuthContext], any[Request[AnyContent]])).thenReturn(auditContextToAuditEventResult)
      when(auditContext.getReasons).thenReturn(mutableMap.empty[String, String])
      when(auditContext.getThrottlingDetails).thenReturn(mutableMap.empty[String, String])

      val expectedThrottledLocation: Location = PersonalTaxAccount
      when(mockThrottlingService.throttle(eqTo(location1), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])).thenReturn(expectedThrottledLocation)

      val mockAuditConnector = mock[AuditConnector]
      val controller = new TestRouterController(
        defaultLocation = location1,
        ruleEngine = ruleEngineStubReturningSomeLocation,
        auditConnector = mockAuditConnector,
        auditContext = Some(auditContext),
        throttlingService = mockThrottlingService
      )

      //then
      val route = await(controller.route)

      checkResult(route, PersonalTaxAccount, ruleApplied)

      //and
      verify(auditContext).toAuditEvent(eqTo(expectedThrottledLocation))(any[HeaderCarrier], any[AuthContext], any[Request[AnyContent]])

      eventually {
        val auditEventCaptor: ArgumentCaptor[ExtendedDataEvent] = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])
        verify(mockAuditConnector).sendEvent(auditEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
        auditEventCaptor.getValue shouldBe mockAuditEvent
      }

      verify(mockThrottlingService).throttle(eqTo(location1), eqTo(auditContext))(eqTo(fakeRequest), eqTo(authContext), any[ExecutionContext])
    }
  }
}

object Mocks extends MockitoSugar {
  def mockThrottlingService = mock[ThrottlingService]

  def mockAuditConnector = mock[AuditConnector]

  def mockMetricsMonitoringService = mock[MetricsMonitoringService]

  def mockTwoStepVerification = {
    val aMock: TwoStepVerification = mock[TwoStepVerification]
    when(aMock.getDestinationVia2SV(any[Location], any[RuleContext], any[TAuditContext])(any[AuthContext], any[Request[AnyContent]], any[HeaderCarrier])).thenAnswer(new Answer[Future[Option[Location]]] {
      override def answer(invocationOnMock: InvocationOnMock): Future[Option[Location]] = {
        Future.successful(Some(invocationOnMock.getArguments()(0).asInstanceOf[Location]))
      }
    })
    aMock
  }
}

class TestRouterController(override val defaultLocation: Location = BusinessTaxAccount,
                           override val metricsMonitoringService: MetricsMonitoringService = Mocks.mockMetricsMonitoringService,
                           override val ruleEngine: RuleEngine,
                           override val throttlingService: ThrottlingService = Mocks.mockThrottlingService,
                           override val auditConnector: AuditConnector = Mocks.mockAuditConnector,
                           override val twoStepVerification: TwoStepVerification = Mocks.mockTwoStepVerification,
                           auditContext: Option[TAuditContext] = None) extends RouterController {

  override protected def authConnector = ???

  override def createAuditContext() = auditContext.getOrElse(AuditContext())
}
