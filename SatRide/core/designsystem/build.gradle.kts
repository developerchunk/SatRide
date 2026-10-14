plugins {
    id("satride.cmp.feature")
}

// Bundled fonts (Baloo 2 for headings, Nunito for body — both OFL, licenses in
// docs/design/). The explicit package keeps the generated `Res` class from colliding with
// other modules' resource accessors.
compose.resources {
    packageOfResClass = "com.developerstring.satride.core.designsystem.generated.resources"
}
