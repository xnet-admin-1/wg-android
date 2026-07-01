import org.gradle.api.Project

fun Project.languageList(): List<String> {
	return fileTree("../app/src/main/res") { include("**/strings.xml") }
		.asSequence()
		.map { stringFile -> stringFile.parentFile.name }
		.map { valuesFolderName -> valuesFolderName.replace("values-", "") }
		.filter { valuesFolderName -> valuesFolderName != "values" }
		.map { languageCode -> languageCode.replace("-r", "_") }
		.distinct()
		.sorted()
		.toList() + "en"
}

fun allowedLicenses(): List<String> {
    return listOf("MIT", "Apache-2.0", "BSD-3-Clause")
}

fun allowedLicenseUrls(): List<String> {
    return listOf("https://jsoup.org/license", "http://opensource.org/licenses/bsd-license.php", "https://github.com/journeyapps/zxing-android-embedded/blob/master/COPYING",
        "https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE", "https://github.com/rafi0101/Android-Room-Database-Backup/blob/master/LICENSE",
        "https://opensource.org/license/mit")
}

fun buildLanguagesArray(languages: List<String>): String {
    return languages.joinToString(separator = ", ") { "\"$it\"" }
}
