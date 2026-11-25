BetterClasseViva

An unofficial Android client built in Kotlin to browse the ClasseViva (Spaggiari) school portal in a more modern, fast, and intuitive way.

✨ Key Features

      Direct Authentication: Quick sign-in using your existing ClasseViva account credentials.

      Dashboard & Overall Average: Automatic GPA/grade average calculation with dedicated charts and progress tracking over time.

      Term-Based Organization: Grades separated by school term (e.g., First Term / Second Term) with custom subject sorting.

      Failing Grades Tracker: Dedicated section to quickly identify subjects below passing grade and monitor recovery progress.

      Smooth Interface: Polished animations, fluid transitions, and tabbed navigation.

🛠️ Tech Stack

      Language: Kotlin

      Platform: Android (Gradle Kotlin DSL / Groovy)

      Architecture & UI: Activity/Fragments, ViewPager2, custom animations, and grade average charts

      Networking & Parsing: HTTP client and dedicated scraper (ClasseVivaScraper) with Kotlin serialization (kotlinx.serialization)

🚀 Getting Started

      Prerequisites

            Android Studio (recent version recommended, e.g., Ladybug / Koala or newer)

      JDK 17 (or the version configured in your Gradle build)

      Physical Android device or emulator with an up-to-date Android SDK

Installation & Build

      Clone the repository:

      git clone https://github.com/<your-username>/betterClasseviva.git 
      cd betterClasseviva


      Open the project folder in Android Studio.

      Wait for Gradle to sync (this will automatically generate local.properties with your local Android SDK path).

      Run the app on an emulator or physical device by clicking Run (Shift + F10), or build via terminal:

      ./gradlew assembleDebug


📁 Project Structure

      betterClasseviva/
      ├── app/
      │   ├── src/
      │   │   ├── main/
      │   │   │   ├── java/com/example/betterclasseviva/
      │   │   │   │   ├── MainActivity.kt         # Main UI management, term tabs, charts, and login
      │   │   │   │   ├── ClasseVivaScraper.kt    # Data fetching and parsing from the ClasseViva portal
      │   │   │   │   ├── Anim.kt                 # UI animation and counter utilities
      │   │   │   │   └── ...                     # Data models (Voto, MedieMateria, ReportCard, etc.)
      │   │   │   └── res/                        # XML layouts, drawables, themes, and resources
      │   │   ├── test/                           # Unit tests
      │   │   └── androidTest/                    # Instrumented tests
      │   └── build.gradle(.kts)
      ├── gradle/
      ├── build.gradle(.kts)
      ├── settings.gradle(.kts)
      └── .gitignore


⚠️ Disclaimer

      This project is independent and unofficial. It is not affiliated with, associated with, authorized, endorsed by, or in any way officially connected with Gruppo Spaggiari Parma S.p.A. or the ClasseViva service.
      
      Credentials entered in the app are used exclusively to communicate directly with ClasseViva services from the user's device.
      
      The author assumes no responsibility for any malfunctions, changes to the portal's APIs/web pages, or misuse of the application.

🤝 Contributing

Contributions are welcome! If you'd like to suggest improvements or report a bug:

Fork the project

Create a feature branch (git checkout -b feature/new-feature)

Commit your changes (git commit -m 'Add new feature')

Push to the branch (git push origin feature/new-feature)

Open a Pull Request
