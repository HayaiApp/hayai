package dev.ahmedmohamed.hayai.extension.managed

enum class ExtensionRepositoryKind {
    Apk,
    JavaScript,
    ;

    companion object {
        fun from(value: String?): ExtensionRepositoryKind = entries.firstOrNull { it.name == value } ?: Apk
    }
}
