package patches.soundcloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import patches.shared.uniqueMatch

private const val LIQUID_GLASS_HOOK = "Lnl/johninnl/soundcloud/LiquidGlassTabBar;"

@Suppress("unused")
val liquidGlassTabBarPatch = bytecodePatch(
    name = "Liquid glass tab bar",
    description = "Replaces SoundCloud's bottom navigation bar with a floating iOS-style Liquid Glass pill featuring real-time backdrop refraction, 7-band chromatic dispersion, damped spring physics, and tilt-reactive specular highlights.",
    default = true,
) {
    compatibleWith(soundcloud)
    dependsOn(originalInput)
    extendWith("extensions/soundcloud.mpe")

    execute {
        listOf(
            "Lcom/soundcloud/android/ui/components/navigations/BottomTabBarRestyle;",
            "Lcom/soundcloud/android/ui/components/navigations/BottomTabBar;",
        ).forEach { targetClass ->
            Fingerprint(
                definingClass = targetClass,
                name = "<init>",
                parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
            ).uniqueMatch().method.apply {
                addInstructions(
                    instructions.count() - 1,
                    """
                        invoke-static {p0}, $LIQUID_GLASS_HOOK->attach(Landroid/view/View;)V
                    """.trimIndent(),
                )
            }
        }
    }
}
