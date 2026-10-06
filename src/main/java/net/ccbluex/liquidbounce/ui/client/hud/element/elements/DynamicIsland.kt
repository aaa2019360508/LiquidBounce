/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 *
 * DynamicIsland —— 从 PojavBounceNew (Minecraft 26.2 / Fabric) 移植至 LiquidBounce Legacy (1.8.9 / Forge)
 *
 * 原文件: features/module/modules/render/ModuleDynamicIsland.kt (641 行, LiquidBounce NextGen API)
 * 移植要点:
 *   - 舍弃 NextGen 的 ClientModule / OverlayRenderEvent / drawQuad / drawRoundedRect(着色器版)
 *   - 改用 Legacy 的 Element (CustomHUD 元素) + RenderUtils + 原版字体
 *   - 保留全部业务逻辑: 状态机 / 尺寸缓动 / 模式优先级 / Tab 列表 / 进度条
 *
 * 模式优先级（与原版一致）:
 *   Tab 列表 > 通知 > 低血警报 > 物品使用 > 方块计数 > 默认信息条
 */
package net.ccbluex.liquidbounce.ui.client.hud.element.elements

import net.ccbluex.liquidbounce.ui.client.hud.element.Border
import net.ccbluex.liquidbounce.ui.client.hud.element.Element
import net.ccbluex.liquidbounce.ui.client.hud.element.ElementInfo
import net.ccbluex.liquidbounce.ui.client.hud.element.Side
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.world.WorldSettings
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

/**
 * CustomHUD DynamicIsland element
 *
 * 灵动岛：屏幕顶部居中的动态信息条。
 * 按优先级在 6 种模式间自动切换，带尺寸缓动动画。
 */
@ElementInfo(name = "DynamicIsland", single = true)
class DynamicIsland(
    x: Double = 0.0, y: Double = 6.0, scale: Float = 1F,
    side: Side = Side(Side.Horizontal.MIDDLE, Side.Vertical.UP),
) : Element("DynamicIsland", x, y, scale, side) {

    /* ============================= 可调节项 ============================= */

    private val uiScale by float("Scale", 1f, 0.6f..1.6f)
    private val sizeEase by float("Size Ease", 13.5f, 4f..30f)
    private val tabFadeSpeed by float("Tab Fade", 9f, 2f..20f)

    private val minWidth by float("Min Width", 250f, 120f..400f)
    private val islandHeight by float("Height", 30f, 22f..48f)
    private val blockHeight by float("Expanded Height", 72f, 50f..120f)
    private val alertHeight by float("Alert Height", 58f, 40f..100f)
    private val topPad by float("Top Padding", 10f, 0f..40f)

    private val bgColor by color("Background", Color(18, 18, 22, 230))
    private val bgHighlight by color("Highlight", Color(40, 40, 48, 90))
    private val textPrimary by color("Text Primary", Color(255, 255, 255, 240))
    private val textSecondary by color("Text Secondary", Color(180, 180, 190, 200))
    private val separatorCol by color("Separator", Color(120, 120, 130, 160))
    private val accent by color("Accent", Color(0x56, 0xB4, 0xE9, 255))
    private val successCol by color("Success", Color(0x22, 0xC5, 0x5E, 255))
    private val failureCol by color("Failure", Color(0xFF, 0x55, 0x55, 255))
    private val goldCol by color("Victory Gold", Color(0xFF, 0xC8, 0x57, 255))
    private val alertCol by color("Alert", Color(0xFF, 0x6B, 0x6B, 255))
    private val progressTrack by color("Progress Track", Color(255, 255, 255, 40))
    private val iconChip by color("Icon Chip", Color(255, 255, 255, 28))

    private val showDefaultInfo by boolean("Default Info", true)
    private val showFps by boolean("Show FPS", true)
    private val showTime by boolean("Show Time", true)
    private val showCoords by boolean("Show Coords", false)
    private val showNotifications by boolean("Notifications", true)
    private val showLowHealth by boolean("Low Health Alert", true)
    private val lowHealthThreshold by float("Low Health %", 0.35f, 0.1f..0.7f)
    private val showItemUse by boolean("Item Use Status", true)
    private val showBlockCount by boolean("Block Count", true)
    private val showTabList by boolean("Tab List Expand", true)
    private val hideInContainer by boolean("Hide In Container", true)
    private val dropShadow by boolean("Drop Shadow", true)
    private val notifyDurationMs by int("Notify Duration Ms", 4000, 1000..10000)

    /* ============================= 常量（对齐原版） ============================= */

    private val PADDING_X = 18f
    private val BLOCK_ICON_X = 12f
    private val BLOCK_ICON_Y = 10f
    private val BLOCK_ICON_BOX = 42f
    private val BLOCK_TEXT_X = 68f
    private val BLOCK_PROGRESS_X = 16f
    private val BLOCK_PROGRESS_Y = 59f
    private val BLOCK_PROGRESS_H = 9f
    private val BLOCK_RIGHT_PADDING = 18f
    private val BLOCK_MIN_WIDTH = 206f
    private val TAB_MIN_WIDTH = 340f
    private val TAB_TOP_PADDING = 16f
    private val TAB_BOTTOM_PADDING = 16f
    private val TAB_SIDE_PADDING = 22f
    private val TAB_ROW_HEIGHT = 14f
    private val TAB_COLUMN_GAP = 20f
    private val TAB_MAX_ROWS = 20

    /* ============================= 通知 API ============================= */

    class NotifyCard(
        val visible: Boolean,
        val icon: String,
        val title: 
