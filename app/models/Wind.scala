package models

import java.lang.Math._

import models.Geo._
import org.joda.time.DateTime
import play.api.libs.json.{Json, Format}


case class Gust(
  position: Point,
  angle: Double, // degrees
  speed: Double,
  radius: Double,
  maxRadius: Double,
  spawnedAt: DateTime
) {
  val pixelPerSecond = 1
  val maxRadiusAfterSeconds = 20

  def update(course: Course, wind: Wind, lastUpdate: DateTime, now: DateTime): Gust = {
    val delta = now.getMillis - lastUpdate.getMillis
    val groundSpeed = (wind.speed + speed) * pixelPerSecond
    val groundDirection = ensure360(angle + 180)
    val newPosition = movePoint(position, delta, groundSpeed, groundDirection)
    val radius = min((now.getMillis - spawnedAt.getMillis) * 0.001 * maxRadius / maxRadiusAfterSeconds, maxRadius)
    copy(position = newPosition, radius = radius)
  }
}

object Gust {
  import scala.util.Random._

  def spawnAll(course: Course) = Seq.fill(course.gustsCount)(spawn(course, initial = true))

  def spawn(course: Course, initial: Boolean = false) = Gust(
    position = (course.area.randomX(100), course.area.top),
    angle = nextInt(10) - 5,
    speed = nextFloat * 10 - 3,
    radius = 0,
    maxRadius = nextInt(100) + 200,
    spawnedAt = DateTime.now
  )
}

case class Wind(
  origin: Double,
  speed: Double,
  gusts: Seq[Gust]
)

object Wind {
  val defaultWindSpeed = 15
  val default = Wind(0, defaultWindSpeed, Nil)

  implicit val gustFormat: Format[Gust] = Json.format[Gust]
  implicit val windFormat: Format[Wind] = Json.format[Wind]
}
