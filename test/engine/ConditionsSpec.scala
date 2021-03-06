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

package engine

import connector.SaReturn
import model.RoutingReason._
import model._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table
import play.api.test.{FakeApplication, FakeRequest}
import uk.gov.hmrc.domain.{Nino, SaUtr}
import uk.gov.hmrc.play.frontend.auth.connectors.domain.{Accounts, CredentialStrength, PayeAccount, SaAccount}
import uk.gov.hmrc.play.frontend.auth.{AuthContext, LoggedInUser, Principal}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConditionsSpec extends UnitSpec with MockitoSugar with WithFakeApplication {

  val configuration = Map[String, Any](
    "business-enrolments" -> "enr1,enr2",
    "self-assessment-enrolments" -> "enr3"
  )

  override lazy val fakeApplication: FakeApplication = FakeApplication(additionalConfiguration = configuration)

  "HasAnyBusinessEnrolment" should {

    "have an audit type specified" in {
      HasAnyBusinessEnrolment.auditType shouldBe Some(HAS_BUSINESS_ENROLMENTS)
    }

    val scenarios =
      Table(
        ("scenario", "enrolments", "expectedResult"),
        ("has business enrolments", Set("enr1"), true),
        ("has no business enrolments", Set.empty[String], false)
      )

    forAll(scenarios) { (scenario: String, enrolments: Set[String], expectedResult: Boolean) =>
      s"be true whether the user has any business enrolments - scenario: $scenario" in {

        implicit val fakeRequest = FakeRequest()
        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val authContext = mock[AuthContext]

        val ruleContext = mock[RuleContext]
        when(ruleContext.activeEnrolments).thenReturn(Future(enrolments))
        val hasAnyBusinessEnrolment = new HasAnyBusinessEnrolment {
          override val businessEnrolments = Set("enr1","enr2")
        }

        val result = await(hasAnyBusinessEnrolment.isTrue(authContext, ruleContext))

        result shouldBe expectedResult
      }
    }
  }

  "HasSelfAssessmentEnrolments" should {

    "have an audit type specified" in {
      HasSelfAssessmentEnrolments.auditType shouldBe Some(HAS_SA_ENROLMENTS)
    }

    val scenarios =
      Table(
        ("scenario", "enrolments", "expectedResult"),
        ("has self assessment enrolments", Set("enr3"), true),
        ("has no self assessment enrolments", Set.empty[String], false)
      )

    forAll(scenarios) { (scenario: String, enrolments: Set[String], expectedResult: Boolean) =>

      implicit val fakeRequest = FakeRequest()
      implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

      val authContext = mock[AuthContext]

      s"be true whether the user has any self assessment enrolment - scenario: $scenario" in {

        lazy val ruleContext = mock[RuleContext]
        when(ruleContext.activeEnrolments).thenReturn(Future(enrolments))
        val hasSelfAssessmentEnrolments = new HasSelfAssessmentEnrolments {
          override val selfAssessmentEnrolments = Set("enr3")
        }

        val result = await(hasSelfAssessmentEnrolments.isTrue(authContext, ruleContext))

        result shouldBe expectedResult
      }
    }
  }

  "HasPreviousReturns" should {

    "have an audit type specified" in {
      HasPreviousReturns.auditType shouldBe Some(HAS_PREVIOUS_RETURNS)
    }

    val scenarios =
      Table(
        ("scenario", "lastSaReturn", "expectedResult"),
        ("has previous returns", SaReturn(previousReturns = true), true),
        ("has no previous returns", SaReturn.empty, false)
      )

    forAll(scenarios) { (scenario: String, lastSaReturn: SaReturn, expectedResult: Boolean) =>

      implicit val fakeRequest = FakeRequest()
      implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

      val authContext = mock[AuthContext]

      s"be true whether the user has any self assessment enrolment - scenario: $scenario" in {

        val ruleContext = mock[RuleContext]
        when(ruleContext.lastSaReturn).thenReturn(Future(lastSaReturn))

        val result = await(HasPreviousReturns.isTrue(authContext, ruleContext))
        result shouldBe expectedResult
      }
    }
  }

  "IsInAPartnership" should {

    "have an audit type specified" in {
      IsInAPartnership.auditType shouldBe Some(IS_IN_A_PARTNERSHIP)
    }

    val scenarios =
      Table(
        ("scenario", "lastSaReturn", "expectedResult"),
        ("is in a partnership", SaReturn(supplementarySchedules = List("partnership")), true),
        ("is not in a partnership", SaReturn.empty, false)
      )

    forAll(scenarios) { (scenario: String, lastSaReturn: SaReturn, expectedResult: Boolean) =>

      implicit val fakeRequest = FakeRequest()
      implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

      val authContext = mock[AuthContext]

      s"be true whether the user has a partnership supplementary schedule - scenario: $scenario" in {

        val ruleContext = mock[RuleContext]
        when(ruleContext.lastSaReturn).thenReturn(Future(lastSaReturn))

        val result = await(IsInAPartnership.isTrue(authContext, ruleContext))
        result shouldBe expectedResult
      }
    }
  }

  "IsSelfEmployed" should {

    "have an audit type specified" in {
      IsSelfEmployed.auditType shouldBe Some(IS_SELF_EMPLOYED)
    }

    val scenarios =
      Table(
        ("scenario", "lastSaReturn", "expectedResult"),
        ("is self employed", SaReturn(supplementarySchedules = List("self_employment")), true),
        ("is not self employed", SaReturn.empty, false)
      )

    forAll(scenarios) { (scenario: String, lastSaReturn: SaReturn, expectedResult: Boolean) =>

      implicit val fakeRequest = FakeRequest()
      implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

      val authContext = mock[AuthContext]

      s"be true whether the user has a self employment supplementary schedule - scenario: $scenario" in {

        val ruleContext = mock[RuleContext]
        when(ruleContext.lastSaReturn).thenReturn(Future(lastSaReturn))

        val result = await(IsSelfEmployed.isTrue(authContext, ruleContext))
        result shouldBe expectedResult
      }
    }
  }

  "LoggedInViaVerify" should {

    "have an audit type specified" in {
      LoggedInViaVerify.auditType shouldBe Some(IS_A_VERIFY_USER)
    }

    val scenarios =
      Table(
        ("scenario", "tokenPresent", "expectedResult"),
        ("has logged in using Verify", false, true),
        ("has not logged in using Verify", true, false)
      )

    forAll(scenarios) { (scenario: String, tokenPresent: Boolean, expectedResult: Boolean) =>

      val authContext = mock[AuthContext]

      s"be true whether the user has logged in using Verify - scenario: $scenario" in {

        implicit val fakeRequest = tokenPresent match {
          case false => FakeRequest()
          case true => FakeRequest().withSession(("token", "token"))
        }

        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val ruleContext = mock[RuleContext]

        val result = await(LoggedInViaVerify.isTrue(authContext, ruleContext))
        result shouldBe expectedResult
      }
    }
  }

  "LoggedInViaGovernmentGateway" should {

    "have an audit type specified" in {
      LoggedInViaGovernmentGateway.auditType shouldBe Some(IS_A_GOVERNMENT_GATEWAY_USER)
    }

    val scenarios =
      Table(
        ("scenario", "tokenPresent", "expectedResult"),
        ("has logged in using GG", true, true),
        ("has not logged in using GG", false, false)
      )

    forAll(scenarios) { (scenario: String, tokenPresent: Boolean, expectedResult: Boolean) =>

      val authContext = mock[AuthContext]

      s"be true whether the user has logged in using Verify - scenario: $scenario" in {

        implicit val fakeRequest = tokenPresent match {
          case false => FakeRequest()
          case true => FakeRequest().withSession(("token", "token"))
        }

        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val ruleContext = mock[RuleContext]

        val result = await(LoggedInViaGovernmentGateway.isTrue(authContext, ruleContext))
        result shouldBe expectedResult
      }
    }
  }

  "HasNino" should {

    "have an audit type specified" in {
      HasNino.auditType shouldBe Some(HAS_NINO)
    }

    val scenarios =
      Table(
        ("scenario", "ninoPresent", "expectedResult"),
        ("user has a NINO", true, true),
        ("user has no NINO", false, false)
      )

    forAll(scenarios) { (scenario: String, ninoPresent: Boolean, expectedResult: Boolean) =>

      s"be true whether the user has a NINO - scenario: $scenario" in {

        val paye = if (ninoPresent) Some(PayeAccount("link", mock[Nino])) else None
        val authContext = AuthContext(mock[LoggedInUser], Principal(None, Accounts(paye = paye)), None)

        implicit val fakeRequest = FakeRequest()

        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val result = await(HasNino.isTrue(authContext, mock[RuleContext]))
        result shouldBe expectedResult
      }
    }
  }

  "HasSaUtr" should {

    "have an audit type specified" in {
      HasSaUtr.auditType shouldBe Some(HAS_SA_UTR)
    }

    val scenarios =
      Table(
        ("scenario", "saUtrPresent", "expectedResult"),
        ("user has a SAUTR", true, true),
        ("user has no SAUTR", false, false)
      )

    forAll(scenarios) { (scenario: String, saUtrPresent: Boolean, expectedResult: Boolean) =>

      s"be true whether the user has SAUTR - scenario: $scenario" in {

        val sa = if (saUtrPresent) Some(SaAccount("", mock[SaUtr])) else None
        val authContext = AuthContext(mock[LoggedInUser], Principal(None, Accounts(sa = sa)), None)

        implicit val fakeRequest = FakeRequest()

        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val result = await(HasSaUtr.isTrue(authContext, mock[RuleContext]))
        result shouldBe expectedResult
      }
    }
  }

  "HasRegisteredFor2SV" should {

    "have an audit type specified" in {
      HasRegisteredFor2SV.auditType shouldBe Some(HAS_REGISTERED_FOR_2SV)
    }

    val scenarios =
      Table(
        ("scenario", "isRegistered"),
        ("return true when user has registered", true),
        ("return false when user has not registered", false)
      )

    forAll(scenarios) { (scenario: String, isRegistered: Boolean) =>

      scenario in {

        val twoFactorAuthOtpId = if (isRegistered) Some("1234") else None
        val authContext = mock[AuthContext]

        implicit val fakeRequest = FakeRequest()

        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)
        val ruleContext = mock[RuleContext]
        when(ruleContext.currentCoAFEAuthority).thenReturn(Future(CoAFEAuthority(twoFactorAuthOtpId)))

        val result = await(HasRegisteredFor2SV.isTrue(authContext, ruleContext))

        result shouldBe isRegistered
        verify(ruleContext).currentCoAFEAuthority
        verifyNoMoreInteractions(ruleContext)
      }
    }
  }

  "HasStrongCredentials" should {

    "have an audit type specified" in {
      HasStrongCredentials.auditType shouldBe Some(HAS_STRONG_CREDENTIALS)
    }

    val scenarios =
      Table(
        ("scenario", "credentialStrength", "expected"),
        ("return false when credential strength None", CredentialStrength.None, false),
        ("return false when credential strength Weak", CredentialStrength.Weak, false),
        ("return true when credential strength Strong", CredentialStrength.Strong, true)
      )

    forAll(scenarios) { (scenario: String, credentialStrength: CredentialStrength, expected: Boolean) =>

      scenario in {
        implicit val fakeRequest = FakeRequest()
        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val loggedInUser = mock[LoggedInUser]
        when(loggedInUser.credentialStrength).thenReturn(credentialStrength)
        val authContext = mock[AuthContext]
        when(authContext.user).thenReturn(loggedInUser)
        val ruleContext = mock[RuleContext]

        val result = await(HasStrongCredentials.isTrue(authContext, ruleContext))

        result shouldBe expected
        verify(authContext).user
        verify(loggedInUser).credentialStrength
        verifyNoMoreInteractions(ruleContext, authContext, loggedInUser)
      }
    }
  }

  "GGEnrolmentsAvailable" should {

    "have an audit type specified" in {
      GGEnrolmentsAvailable.auditType shouldBe Some(GG_ENROLMENTS_AVAILABLE)
    }

    val scenarios =
      Table(
        ("scenario", "ggEnrolmentsAvailable"),
        ("be true when GG is available", true),
        ("be false GG is not available", false)
      )

    forAll(scenarios) { (scenario: String, ggEnrolmentsAvailable: Boolean) =>

      scenario in {
        implicit val fakeRequest = FakeRequest()
        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val authContext = mock[AuthContext]
        val ruleContext = mock[RuleContext]

        val expectedResult = ggEnrolmentsAvailable match {
          case true => Future.successful(Set.empty[String])
          case false => Future.failed(new RuntimeException())
        }
        when(ruleContext.activeEnrolments).thenReturn(expectedResult)

        val result = await(GGEnrolmentsAvailable.isTrue(authContext, ruleContext))

        result shouldBe ggEnrolmentsAvailable
        verify(ruleContext).activeEnrolments
        verifyNoMoreInteractions(ruleContext, authContext)
      }
    }
  }

  "SAReturnAvailable" should {

    "have an audit type specified" in {
      SAReturnAvailable.auditType shouldBe Some(SA_RETURN_AVAILABLE)
    }

    val scenarios =
      Table(
        ("scenario", "saReturnAvailable"),
        ("be true when SA is available", true),
        ("be false SA is not available", false)
      )

    forAll(scenarios) { (scenario: String, saReturnAvailable: Boolean) =>

      scenario in {
        implicit val fakeRequest = FakeRequest()
        implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

        val authContext = mock[AuthContext]
        val ruleContext = mock[RuleContext]

        val expectedResult = saReturnAvailable match {
          case true => Future.successful(mock[SaReturn])
          case false => Future.failed(new RuntimeException())
        }
        when(ruleContext.lastSaReturn).thenReturn(expectedResult)

        val result = await(SAReturnAvailable.isTrue(authContext, ruleContext))

        result shouldBe saReturnAvailable
        verify(ruleContext).lastSaReturn
        verifyNoMoreInteractions(ruleContext, authContext)
      }
    }
  }

  "AnyOtherRuleApplied" should {

    "not have any audit type specified" in {
      AnyOtherRuleApplied.auditType shouldBe None
    }

    val authContext = mock[AuthContext]

    "always be true" in {

      implicit val fakeRequest = FakeRequest()
      implicit val hc = HeaderCarrier.fromHeadersAndSession(fakeRequest.headers)

      val ruleContext = mock[RuleContext]

      val result = await(AnyOtherRuleApplied.isTrue(authContext, ruleContext))
      result shouldBe true
    }
  }
}
