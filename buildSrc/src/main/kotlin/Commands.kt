import org.gradle.api.Project
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Date

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getCommitCount(): String {
    return runCommand("git rev-list --count HEAD")
    // return "1"
}

fun Project.getBetaCount(): String {
    val betaTags = runCommand("git tag -l --sort=refname v${AndroidVersions.versionName}-b*")
    return String.format("%02d", if (betaTags.isNotEmpty()) {
        val betaTag = betaTags.split("\n").last().substringAfter("-b").toIntOrNull()
        ((betaTag ?: 0) + 1)
    } else {
        1
    })
    // return "1"
}

fun Project.getNightlyBuildNumber(): String {
    val supplied = providers.environmentVariable("NIGHTLY_BUILD_NUMBER").orNull?.trim()
    require(supplied == null || supplied.matches(Regex("[0-9]+"))) {
        "NIGHTLY_BUILD_NUMBER must contain only decimal digits."
    }
    return supplied ?: getCommitCount()
}


fun Project.getGitSha(): String {
    return runCommand("git rev-parse --short HEAD")
    // return "1"
}

fun Project.getBuildTime(): String {
    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
    df.timeZone = TimeZone.getTimeZone("UTC")
    return df.format(Date())
}

fun Project.runCommand(command: String): String {
    return providers
        .exec {
            commandLine = command.split(" ")
        }.standardOutput.asText
        .get()
        .trim()
}
