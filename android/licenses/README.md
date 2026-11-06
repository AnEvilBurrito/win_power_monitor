# Third-party license notices

**WPM's Android application is GPL-3.0-only**, under the repository's
[LICENSE](../../LICENSE). Nothing in this directory changes that license.

`Apache-2.0.txt` is the license text for the existing AndroidX, Compose, Kotlin
and other Apache-2.0 dependencies. It is retained to meet their redistribution
requirements, not to license WPM's own code under Apache.

The Android release source archive includes each resolved runtime dependency's
source JAR, POM, recorded license and extracted notices, plus the pinned native
graphics-path source omitted from its Maven source JAR. Those third-party
materials retain their upstream licenses. The release exporter fails for an
unreviewed license rather than silently replacing it with GPL text.
