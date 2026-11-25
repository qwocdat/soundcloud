package patches.soundcloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import patches.shared.uniqueMatch

private const val LIQUID_GLASS_HOOK = "Lnl/johninnl/soundcloud/LiquidGlassTabBar;"

@Suppress("unused")
val liquidGlassTabBarPatch = bytecodePatch(
    name = "Liquid glass tab bar",
    description = "Replaces SoundCloud's bottom navigation bar and mini-player with floating iOS-style Liquid Glass pills featuring real-time backdrop refraction, 7-band chromatic dispersion, separated Search/Menu circle, Author tab, stacked scroll mode, and in-app TabBar settings.",
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

        // Inject TabBar Liquid Glass settings entry into SoundCloud's main Settings screen
        Fingerprint(
            definingClass = "Lcom/soundcloud/android/settings/main/SettingsFragment;",
            name = "onCreateView",
            parameters = listOf(
                "Landroid/view/LayoutInflater;",
                "Landroid/view/ViewGroup;",
                "Landroid/os/Bundle;",
            ),
            returnType = "Landroid/view/View;",
        ).uniqueMatch().method.apply {
            val retIdx = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            if (retIdx >= 0) {
                val reg = (instructions[retIdx] as OneRegisterInstruction).registerA
                addInstructions(
                    retIdx,
                    """
                        invoke-static {v$reg}, $LIQUID_GLASS_HOOK->wrapSettingsView(Landroid/view/View;)Landroid/view/View;
                        move-result-object v$reg
                    """.trimIndent(),
                )
            }
        }
    }
}
