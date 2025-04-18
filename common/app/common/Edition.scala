package common

import navigation.EditionNavLinks

import java.util.Locale
import org.joda.time.DateTimeZone
import play.api.libs.json._
import play.api.mvc.RequestHeader

import java.time.ZoneId

// describes the ways in which editions differ from each other
abstract class Edition(
    val id: String,
    val displayName: String,
    val timezone: DateTimeZone,
    val locale: Option[Locale],
    val networkFrontId: String,
    val editionalisedSections: Seq[String] = Edition.commonEditionalisedSections,
    val navigationLinks: EditionNavLinks,
) {
  val homePagePath: String = s"/$networkFrontId"

  def isEditionalised(sectionId: String): Boolean = editionalisedSections.contains(sectionId)
  def timezoneId = ZoneId.of(timezone.getID)
}

object Edition {
  // gives templates an implicit edition
  implicit def edition(implicit request: RequestHeader): Edition = this(request)

  lazy val defaultEdition: Edition = editions.Uk
  def editionsSupportingSection(sectionId: String): Seq[Edition] =
    allEditions.filter(_.isEditionalised(sectionId))

  lazy val allEditions = List(
    editions.Uk,
    editions.Us,
    editions.Au,
    editions.International,
    editions.Europe,
  )

  lazy val localeByEdition: Map[Edition, Locale] =
    (for {
      edition <- allEditions
      locale <- edition.locale
    } yield edition -> locale).toMap

  lazy val commonEditionalisedSections: Seq[String] = Seq(
    "", // network front
    "business",
    "business-to-business",
    "commentisfree",
    "culture",
    "money",
    "sport",
    "technology",
    "media",
    "environment",
    "film",
    "lifeandstyle",
    "travel",
    "tv-and-radio",
  )

  private def editionFromRequest(request: RequestHeader): String = {
    // override for Ajax calls
    val editionFromParameter = request.getQueryString("_edition")

    // set upstream from geo location/ user preference
    val editionFromHeader = request.headers.get("X-Gu-Edition")

    // NOTE: this only works on dev machines for local testing
    // in production no cookies make it this far
    val editionFromCookie = request.cookies.get("GU_EDITION").map(_.value)

    editionFromParameter
      .orElse(editionFromHeader)
      .orElse(editionFromCookie)
      .getOrElse(defaultEdition.id)
  }

  def apply(request: RequestHeader): Edition = {
    val edition = editionFromRequest(request)
    allEditions.find(_.id.equalsIgnoreCase(edition)).getOrElse(defaultEdition)
  }

  def others(implicit request: RequestHeader): Seq[Edition] = {
    val currentEdition = Edition(request)
    allEditions.filterNot(_ == currentEdition)
  }

  def othersWithBetaEditions(edition: Edition): Seq[Edition] = allEditions.filterNot(_ == edition)

  def byId(id: String): Option[Edition] = allEditions.find(_.id.equalsIgnoreCase(id))
  def byNetworkFrontId(id: String): Option[Edition] = allEditions.find(_.networkFrontId == id)

  implicit val editionWrites: Writes[Edition] = new Writes[Edition] {
    def writes(edition: Edition): JsValue = Json.obj("id" -> edition.id)
  }

  implicit val editionReads: Reads[Edition] = {
    (__ \ "id").read[String] map (Edition.byId(_).getOrElse(defaultEdition))
  }

  lazy val editionRegex = allEditions.map(_.homePagePath.replaceFirst("/", "")).mkString("|")
  private lazy val EditionalisedFront = s"""^/($editionRegex)$$""".r

  private lazy val EditionalisedId = s"^/($editionRegex)/([\\w\\d-]+)$$".r

  /*
  This method is used for working out what alternate edition
  versions of pages Google might want to know about.
  We have to be careful that pages that use Hreflang alternates all
  point to each other in a consistent group and that no two editions have the same locale.

  https://developers.google.com/search/docs/advanced/crawling/localized-versions
   */
  def localizedEditionLinks(request: RequestHeader): Seq[EditionLink] = {
    val path = request.path

    path match {
      case EditionalisedId(editionNetworkId, sectionId)
          if editionsSupportingSection(sectionId).nonEmpty
            && editionIsIncludedInHreflangAlternates(editionNetworkId) =>
        for {
          edition <- editionsSupportingSection(sectionId)
          locale <- localeByEdition.get(edition)
        } yield EditionLink(edition, s"/$sectionId", locale)

      case EditionalisedFront(networkFrontId) if editionIsIncludedInHreflangAlternates(networkFrontId) =>
        for {
          (edition, locale) <- localeByEdition.toSeq
        } yield EditionLink(edition, "/", locale)
      case _ => Nil
    }
  }
  /* We might choose to exclude certain editions from Hreflang alternates because we can't have more than one Edition possessing the same language code */
  private def editionIsIncludedInHreflangAlternates(editionNetworkId: String) = {
    Edition.byNetworkFrontId(editionNetworkId).exists(_.locale.isDefined)
  }
}

object Editionalise {

  import Edition.defaultEdition

  // TODO - understand RSS

  def apply(id: String, edition: Edition): String = {
    if (edition.isEditionalised(id)) id match {
      case "" => edition.homePagePath.replaceFirst("/", "")
      case _  => s"${edition.id.toLowerCase}/$id"
    }
    else if (defaultEdition.isEditionalised(id)) {
      s"${defaultEdition.id.toLowerCase}/$id"
    } else {
      id
    }
  }
}

case class EditionLink(edition: Edition, path: String, locale: Locale)
