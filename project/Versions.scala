package com.twitter.sbt

import sbt._
import Keys._

import java.util.regex.{Matcher, Pattern}

/**
 * algebraic version type. You can either have semantic versions (major.minor.patch.extra)
 * or freeform ones.
 */
sealed trait Version {
  def incPatch(): Option[Version] = None
  def incMinor(): Option[Version] = None
  def incMajor(): Option[Version] = None
  def snapshot(): Boolean
  def toSnapshot(): Version
  def stripSnapshot(): Version
}

/**
 * represents a semantic version
 */
case class SemanticVersion(major: Int, minor: Int, patch: Int, release: Option[String], snapshot: Boolean) extends Version {
  override def incPatch() = Some(copy(patch = patch + 1))
  override def incMinor() = Some(copy(minor = minor + 1, patch = 0))
  override def incMajor() = Some(copy(major = major + 1, minor = 0, patch = 0))
  def stripSnapshot() = copy(snapshot = false)
  def toSnapshot() = copy(snapshot = true)
  override def toString() = {
    val extraSlot = release.map(r => ".%s".format(r)).getOrElse("")
    val snapshotSlot = if (snapshot) {
      "-SNAPSHOT"
    } else {
      ""
    }
    "%s.%s.%s%s%s".format(major, minor, patch, extraSlot, snapshotSlot)
  }
}

/**
 * represents a freeform string version
 */
case class RawVersion(version: String, snapshot: Boolean) extends Version {
  def this(version: String) = this(Version.stripSnapshot(version), version.endsWith("SNAPSHOT"))
  def stripSnapshot() = copy(snapshot = false)
  def toSnapshot() = copy(snapshot = true)
  override def toString() = {
    if (snapshot) {
      "%s-SNAPSHOT".format(version)
    } else {
      version
    }
  }
}

/**
 * utilities for working with versions
 */
object Version {
  def stripSnapshot(s: String) = {
    s.replaceAll("""-?SNAPSHOT$""","")
  }

  def isSnapshot(s: String) = {
    s.endsWith("SNAPSHOT")
  }

  implicit def toVersion(v: String): Version = {
    val stripped = stripSnapshot(v)
    stripped.split("\\.") match {
      case Array(major, minor, patch) => {
        SemanticVersion(Integer.parseInt(major),
                        Integer.parseInt(minor),
                        Integer.parseInt(patch),
                        None,
                        isSnapshot(v))
      }
      case Array(major, minor, patch, release) => {
        SemanticVersion(Integer.parseInt(major),
                        Integer.parseInt(minor),
                        Integer.parseInt(patch),
                        Some(release),
                        isSnapshot(v))
      }
      case _ => RawVersion(stripped, isSnapshot(v))
    }
  }
}

/**
 * provides tasks for changing the current project's version
 */
object VersionManagement extends Plugin {
  import Version._

  /**
   * apply f to the current version, update versions in .sbt and build .scala files,
   * mutate project state
   */
  def changeVersion(state: State, log: Logger)(f: Version => Option[Version]): State = {
    val extracted = Project.extract(state)
    import extracted._
    val base = extracted.get(Keys.baseDirectory)
    val ver = extracted.get(Keys.version)
    val regexes = extracted.get(VersionManagement.versionRegexes)
    val from = extracted.get(Keys.version)
    val newVersion = f(from) match {
      case Some(to) => {
        val files = (PathFinder(base / "project") ** "*.scala").get ++
          Seq((base / "build.sbt")) ++
          Seq((base / ".." / "build.sbt"))
        val matchers = regexes.map(Pattern.compile(_))
        files.filter(_.exists).headOption.foreach { f =>
          log.info("Setting version %s in file %s".format(to, f))
          writeNewVersion(f, matchers, from, to)
        }
        Some(to)
      }
      case _ => {
        log.warn("Version %s is not a semantic version, cannot change".format(from))
        None
      }
    }
    State.stateOps(state).reload
  }

  /**
   * regexes to find versions
   */
  val versionRegexes = SettingKey[Seq[String]]("version-regexes", "a list of regexes to use to replace versions")

  /**
   * given a file, a set of matchers, an existing version and a new version,
   * <ul>
   * <li>read all the lines</li>
   * <li>look for version declarations of the existing version</li>
   * <li>replace with new version</li>
   */
  def writeNewVersion(f: File, matchers: Seq[Pattern], from: Version, to: Version) {
    var shouldWrite = false
    val newLines = IO.reader(f) { reader =>
      IO.foldLines(reader, Seq[String]()) { (lines, line) =>
        lines :+ matchers.foldLeft(line) { (line, r) =>
          val verMatcher = r.matcher(line)
          if (verMatcher.find() && line.contains(from.toString)) {
            shouldWrite = true
            line.replaceAll(from.toString, to.toString)
          } else {
            line
          }
        }
      }
    }
    if (shouldWrite) {
      IO.writeLines(f, newLines)
    }
  }

  // commands to call changeVersion with a variety of version mutations
  def versionBumpMajor = Command.command(
    "version-bump-major",
    "Bump the major version number (for example, 2.1.4 -> 3.0.0)",
    ""
  ) { state =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { _.incMajor }
  }
  def versionBumpMinor = Command.command(
    "version-bump-minor",
    "Bump the minor version number (for example, 2.1.4 -> 2.2.0)",
    ""
  ) { state =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { _.incMinor }
  }
  def versionBumpPatch = Command.command(
    "version-bump-patch",
    "Bump the patch version number (for example, 2.1.4 -> 2.1.5)",
    ""
  ) { state =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { _.incPatch }
  }
  def versionToSnapshot = Command.command(
    "version-to-snapshot",
    "Convert the current version into a snapshot release (for example, 2.1.4 -> 2.1.4-SNAPSHOT)",
    ""
  ) { state =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { v => Some(v.toSnapshot) }
  }
  def versionToStable = Command.command(
    "version-to-stable",
    "Convert the current version into a stable release (for example, 2.1.4-SNAPSHOT -> 2.1.4)",
    ""
  ) { state =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { v => Some(v.stripSnapshot) }
  }
  def versionSet = Command.single(
    "version-set",
    ("version-set version", "Manually set the current version"),
    ""
  ) { (state: State, v: String) =>
    val log = CommandSupport.logger(state)
    changeVersion(state, log) { old => Some(v) }
  }

  val newSettings = Seq(
    // very crude, but really, if you have fancy pants version settings then you're on your own
    versionRegexes := Seq("""\bversion\s+:=\s*("(.*?)")""")
  )

  /**
   * make commands available to projects
   */
  override lazy val settings = Seq(commands ++= Seq(
    versionBumpMajor,
    versionBumpMinor,
    versionBumpPatch,
    versionToSnapshot,
    versionToStable,
    versionSet))
}
