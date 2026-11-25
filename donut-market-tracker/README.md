# DonutSMP Market Tracker (Fabric, Minecraft 1.21.1)

Stock-market style dashboard for the DonutSMP economy. Client-side only.

## Build
1. Easiest: download the Fabric template from https://fabricmc.net/develop/template/ (1.21.1), then copy
   `src/`, `build.gradle`, `gradle.properties` and `settings.gradle` from this folder over it
   (this keeps the Gradle wrapper). Or have Gradle 8.8+ and JDK 21 installed and run `gradle build` here.
2. Run `./gradlew build` -> jar is in `build/libs/donut-market-tracker-1.0.0.jar` (not the `-sources` jar).
3. Run `./gradlew runClient` to test in a dev client.

## Use
- Put the jar + Fabric API in your Modrinth instance's mods folder (Fabric 1.21.1).
- On DonutSMP run `/api`, then `/dmarket key <token>`.
- Press **M** (rebind in Controls) or run `/dmarket` to open the dashboard.
- Data: `.minecraft/config/donutmarket/` (`config.json`, `prices.json`).

## Commands
/dmarket, /dmarket key <token>, /dmarket status
