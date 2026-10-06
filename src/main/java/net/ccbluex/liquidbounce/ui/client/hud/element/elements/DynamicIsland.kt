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
        val title: String,
        val message: String,
        val accentColor: Color,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        companion object {
            val EMPTY = NotifyCard(false, "", "", "", Color.WHITE, 0L)
        }
    }

    private var currentNotify = NotifyCard.EMPTY
    private var notifyExpireAt = 0L

    fun success(message: String) = showNotify("Success", "√", message, successCol)
    fun failure(message: String) = showNotify("Failure", "x", message, failureCol)
    fun error(message: String) = showNotify("Error", "!", message, failureCol)
    fun victory() = showNotify("Victory!", "*", "You are the ultimate winner.", goldCol)

    private fun showNotify(title: String, icon: String, message: String, accentColor: Color) {
        currentNotify = NotifyCard(true, icon, title, message, accentColor)
        notifyExpireAt = System.currentTimeMillis() + notifyDurationMs
    }

    private fun isNotifyActive() =
        showNotifications && currentNotify.visible && System.currentTimeMillis() < notifyExpireAt

    /* ============================= 动画状态 ============================= */

    /** 当前渲染宽度（做缓动） */
    private var currentWidth = 0f
    private var currentHeight = 0f

    /** Tab 列表淡入淡出 */
    private var tabAlpha = 0f

    /** 上一帧时间戳 */
    private var lastFrameMs = System.currentTimeMillis()

    /** 缓动：指数趋近 */
    private fun ease(current: Float, target: Float, speed: Float, deltaSec: Float): Float {
        if (speed <= 0f) return target
        val factor = 1f - (1f / (1f + speed * deltaSec * 6f))
        return current + (target - current) * factor
    }

    /* ============================= 模式判定 ============================= */

    private enum class Mode { TAB, NOTIFY, LOW_HEALTH, ITEM_USE, BLOCK_COUNT, DEFAULT }

    private fun resolveMode(): Mode {
        if (showTabList && isTabListOpen()) return Mode.TAB
        if (isNotifyActive()) return Mode.NOTIFY
        if (showLowHealth && isLowHealth()) return Mode.LOW_HEALTH
        if (showItemUse && isUsingItem()) return Mode.ITEM_USE
        if (showBlockCount && heldBlockCount() > 0) return Mode.BLOCK_COUNT
        return Mode.DEFAULT
    }

    /** 1.8.9: 玩家列表打开时 guiIngame 的 tab 状态；用 currentScreen 简化判断 */
    private fun isTabListOpen(): Boolean = false

    private fun isLowHealth(): Boolean {
        val player = mc.thePlayer ?: return false
        val maxHealth = player.maxHealth
        if (maxHealth <= 0f) return false
        return (player.health / maxHealth) <= lowHealthThreshold
    }

    private fun isUsingItem(): Boolean {
        val player = mc.thePlayer ?: return false
        val stack = player.heldItem ?: return false
        // 1.8.9: ItemStack.getItemUseAction() 判断是否在使用
        return player.isUsingItem && stack.item != null
    }

    private fun heldBlockCount(): Int {
        val player = mc.thePlayer ?: return 0
        val stack = player.heldItem ?: return 0
        return if (stack.item is ItemBlock) stack.stackSize else 0
    }

    private fun hiddenByContainer(): Boolean {
        if (!hideInContainer) return false
        val screen = mc.currentScreen ?: return false
        val name = screen.javaClass.simpleName
        return name.contains("GuiChest") || name.contains("GuiInventory") ||
            name.contains("GuiContainerCreative") || name.contains("GuiFurnace") ||
            name.contains("GuiDispenser") || name.contains("GuiHopper") ||
            name.contains("GuiShulkerBox")
    }

    /* ============================= 绘制入口 ============================= */

    override fun drawElement(): Border? {
        if (mc.thePlayer == null || hiddenByContainer()) return null

        val now = System.currentTimeMillis()
        val deltaSec = ((now - lastFrameMs).coerceIn(0L, 100L)) / 1000f
        lastFrameMs = now

        val sc = ScaledResolution(mc)
        val screenW = sc.scaledWidth.toFloat()

        val mode = resolveMode()

        // ---- 目标尺寸 ----
        val targetW: Float
        val targetH: Float
        when (mode) {
            Mode.TAB -> {
                targetW = max(TAB_MIN_WIDTH, tabRequiredWidth())
                targetH = tabRequiredHeight()
            }
            Mode.NOTIFY -> {
                targetW = max(minWidth, notifyRequiredWidth())
                targetH = alertHeight
            }
            Mode.LOW_HEALTH -> {
                targetW = max(minWidth, 270f)
                targetH = alertHeight
            }
            Mode.ITEM_USE, Mode.BLOCK_COUNT -> {
                targetW = max(BLOCK_MIN_WIDTH, 224f)
                targetH = blockHeight
            }
            Mode.DEFAULT -> {
                targetW = max(minWidth, defaultRequiredWidth())
                targetH = islandHeight
            }
        }

        // ---- 缓动 ----
        if (currentWidth <= 0f) {
            currentWidth = targetW
            currentHeight = targetH
        }
        currentWidth = ease(currentWidth, targetW, sizeEase, deltaSec)
        currentHeight = ease(currentHeight, targetH, sizeEase, deltaSec)

        // Tab 淡入淡出
        val tabTargetAlpha = if (mode == Mode.TAB) 1f else 0f
        tabAlpha = ease(tabAlpha, tabTargetAlpha, tabFadeSpeed, deltaSec)

        // ---- 布局 ----
        val w = currentWidth * uiScale
        val h = currentHeight * uiScale
        val x0 = (screenW - w) / 2f
        val y0 = topPad
        val x1 = x0 + w
        val y1 = y0 + h

        val radius = min(h / 2f, 18f)

        // ---- 阴影（1.8.9 无阴影着色器，用多层半透明矩形模拟）----
        if (dropShadow) {
            RenderUtils.drawRoundedRect(
                x0 + 2f, y0 + 3f, x1 + 2f, y1 + 3f,
                Color(0, 0, 0, 60).rgb, radius + 2f
            )
        }

        // ---- 岛体 ----
        RenderUtils.drawRoundedRect(x0, y0, x1, y1, bgColor.rgb, radius)

        // ---- 顶部高光 ----
        RenderUtils.drawRect(x0 + radius, y0 + 1f, x1 - radius, y0 + 2f, bgHighlight.rgb)

        // ---- 内容 ----
        when (mode) {
            Mode.TAB -> drawTabList(x0, y0, x1, y1)
            Mode.NOTIFY -> drawNotification(x0, y0, x1, y1)
            Mode.LOW_HEALTH -> drawLowHealth(x0, y0, x1, y1)
            Mode.ITEM_USE -> drawItemUse(x0, y0, x1, y1)
            Mode.BLOCK_COUNT -> drawBlockCount(x0, y0, x1, y1)
            Mode.DEFAULT -> drawDefaultInfo(x0, y0, x1, y1)
        }

        return Border(x0, y0, x1, y1)
    }

    /* ============================= 各模式绘制 ============================= */

    private fun drawDefaultInfo(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!showDefaultInfo) return
        val cy = (y0 + y1) / 2f
        val font = Fonts.minecraftFont
        val fh = font.FONT_HEIGHT
        val textY = cy - fh / 2f

        var cursor = x0 + PADDING_X

        // 左侧：客户端标识
        cursor = drawChip(cursor, cy, "LB", accent)
        cursor = drawText(cursor, textY, "LiquidBounce", textPrimary)

        // 右侧：FPS / 时间 / 坐标
        var right = x1 - PADDING_X

        if (showCoords) {
            val p = mc.thePlayer
            val coords = "${p.posX.roundToInt()} ${p.posY.roundToInt()} ${p.posZ.roundToInt()}"
            right = drawTextRight(right, textY, coords, textSecondary) - 12f
            drawTextRight(right, textY, "XYZ", separatorCol)
            right -= 30f
        }

        if (showTime) {
            val time = String.format(Locale.ROOT, "%02d:%02d",
                java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE))
            right = drawTextRight(right, textY, time, textPrimary) - 12f
        }

        if (showFps) {
            val fps = net.minecraft.client.Minecraft.getDebugFPS()
            val fpsColor = when {
                fps >= 120 -> successCol
                fps >= 60 -> textPrimary
                fps >= 30 -> goldCol
                else -> failureCol
            }
            right = drawTextRight(right, textY, "$fps", fpsColor) - 6f
            drawTextRight(right, textY, "FPS", separatorCol)
        }
    }

    private fun drawNotification(x0: Float, y0: Float, x1: Float, y1: Float) {
        val card = currentNotify
        val cy = (y0 + y1) / 2f
        val font = Fonts.minecraftFont
        val fh = font.FONT_HEIGHT

        // 图标块
        val chipSize = 26f
        val chipX = x0 + 14f
        val chipY = cy - chipSize / 2f
        RenderUtils.drawRoundedRect(
            chipX, chipY, chipX + chipSize, chipY + chipSize,
            iconChip.rgb, chipSize / 2f
        )
        drawTextCentered(chipX, chipY + (chipSize - fh) / 2f, chipSize, card.icon, card.accentColor)

        // 标题 + 正文
        var tx = chipX + chipSize + 12f
        tx = drawText(tx, cy - fh - 2f, card.title, card.accentColor)
        drawText(tx - (card.title.length * 6f) - 0f, cy + 2f, card.message, textSecondary)

        // 右侧剩余时间条
        val remain = (notifyExpireAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val ratio = (remain.toFloat() / notifyDurationMs).coerceIn(0f, 1f)
        val barW = 3f
        RenderUtils.drawRoundedRect(
            x1 - 16f, y0 + 10f, x1 - 16f + barW, y1 - 10f,
            progressTrack.rgb, barW / 2f
        )
        val filledH = (y1 - 10f - (y0 + 10f)) * ratio
        RenderUtils.drawRoundedRect(
            x1 - 16f, y1 - 10f - filledH, x1 - 16f + barW, y1 - 10f,
            card.accentColor.rgb, barW / 2f
        )
    }

    private fun drawLowHealth(x0: Float, y0: Float, x1: Float, y1: Float) {
        val player = mc.thePlayer ?: return
        val font = Fonts.minecraftFont
        val fh = font.FONT_HEIGHT

        val pulse = (0.6f + 0.4f * kotlin.math.sin(System.currentTimeMillis() / 220.0).toFloat())
            .coerceIn(0f, 1f)
        val alertColor = blend(alertCol, failureCol, pulse)

        val cy = (y0 + y1) / 2f

        val chipSize = 26f
        val chipX = x0 + 14f
        RenderUtils.drawRoundedRect(
            chipX, cy - chipSize / 2f, chipX + chipSize, cy + chipSize / 2f,
            alertColor.rgb, chipSize / 2f
        )
        drawTextCentered(chipX, cy - fh / 2f, chipSize, "!", Color.BLACK)

        var tx = chipX + chipSize + 12f
        tx = drawText(tx, cy - fh - 2f, "Low Health", alertColor)

        val hp = player.health
        val maxHp = player.maxHealth
        val pct = (hp / maxHp * 100f).roundToInt()
        drawText(tx - ("Low Health".length * 6f), cy + 2f, "$pct%  ($hp/$maxHp)", textSecondary)

        // 血量条
        val barY = y1 - 8f
        val barX0 = x0 + 14f
        val barX1 = x1 - 14f
        RenderUtils.drawRoundedRect(barX0, barY, barX1, barY + 4f, progressTrack.rgb, 2f)
        val fill = (barX1 - barX0) * (hp / maxHp).coerceIn(0f, 1f)
        RenderUtils.drawRoundedRect(barX0, barY, barX0 + fill, barY + 4f, alertColor.rgb, 2f)
    }

    private fun drawItemUse(x0: Float, y0: Float, x1: Float, y1: Float) {
        val player = mc.thePlayer ?: return
        val stack = player.heldItem ?: return
        drawItemCard(x0, y0, x1, y1, stack, "Using", accent)
    }

    private fun drawBlockCount(x0: Float, y0: Float, x1: Float, y1: Float) {
        val player = mc.thePlayer ?: return
        val stack = player.heldItem ?: return
        val count = heldBlockCount()
        if (count <= 0) return
        drawItemCard(x0, y0, x1, y1, stack, "Blocks", accent, count)
    }

    private fun drawItemCard(
        x0: Float, y0: Float, x1: Float, y1: Float,
        stack: ItemStack, label: String, accentColor: Color, count: Int = -1,
    ) {
        val font = Fonts.minecraftFont
        val fh = font.FONT_HEIGHT

        // 物品图标框
        val boxX = x0 + BLOCK_ICON_X
        val boxY = y0 + BLOCK_ICON_Y
        RenderUtils.drawRoundedRect(boxX, boxY, boxX + BLOCK_ICON_BOX, boxY + BLOCK_ICON_BOX, iconChip.rgb, 10f)

        // 物品名
        val name = stack.displayName
        drawText(x0 + BLOCK_TEXT_X, boxY + 6f, name, textPrimary)

        // 数量
        if (count >= 0) {
            drawText(x0 + BLOCK_TEXT_X, boxY + 6f + fh + 3f, "x$count", accentColor)
        } else {
            val meta = stack.item.getUnlocalizedName().replace("item.", "").replace("tile.", "")
            drawText(x0 + BLOCK_TEXT_X, boxY + 6f + fh + 3f, meta, textSecondary)
        }

        // 进度条
        val barX0 = x0 + BLOCK_PROGRESS_X
        val barX1 = x1 - BLOCK_RIGHT_PADDING
        val barY = y0 + BLOCK_PROGRESS_Y
        RenderUtils.drawRoundedRect(barX0, barY, barX1, barY + BLOCK_PROGRESS_H, progressTrack.rgb, BLOCK_PROGRESS_H / 2f)

        val progress = if (mc.thePlayer.isUsingItem) {
            val total = stack.maxItemUseDuration.coerceAtLeast(1)
            (total - mc.thePlayer.itemInUseCount).toFloat() / total
        } else {
            1f
        }.coerceIn(0f, 1f)

        val fill = (barX1 - barX0) * progress
        if (fill > 0f) {
            RenderUtils.drawRoundedRect(barX0, barY, barX0 + fill, barY + BLOCK_PROGRESS_H, accentColor.rgb, BLOCK_PROGRESS_H / 2f)
        }

        // 左上角标签
        drawText(x0 + BLOCK_PROGRESS_X, y0 + 8f, label, separatorCol)
    }

    private fun drawTabList(x0: Float, y0: Float, x1: Float, y1: Float) {
        val alpha = (tabAlpha * 255).roundToInt().coerceIn(0, 255)
        if (alpha <= 4) return

        val players = mc.theWorld?.playerEntities ?: return
        val font = Fonts.minecraftFont
        val fh = font.FONT_HEIGHT

        val rows = min(players.size, TAB_MAX_ROWS)
        val cols = if (rows > 10) 2 else 1
        val perCol = (rows + cols - 1) / cols

        val startY = y0 + TAB_TOP_PADDING
        var colIndex = 0
        var rowIndex = 0

        val colWidth = (x1 - x0 - TAB_SIDE_PADDING * 2 - TAB_COLUMN_GAP * (cols - 1)) / cols

        players.take(rows).forEach { p ->
            val cx = x0 + TAB_SIDE_PADDING + colIndex * (colWidth + TAB_COLUMN_GAP)
            val cy = startY + rowIndex * TAB_ROW_HEIGHT

            val isSelf = p == mc.thePlayer
            val name = p.name
            val color = when {
                isSelf -> failureCol
                else -> withAlpha(textPrimary, alpha)
            }

            drawText(cx, cy, name, color)

            // 血量小条
            val hpRatio = (p.health / p.maxHealth).coerceIn(0f, 1f)
            val hpBarW = 24f
            val hpBarX = cx + colWidth - hpBarW - 4f
            RenderUtils.drawRect(hpBarX, cy + 3f, hpBarX + hpBarW, cy + 7f, withAlpha(progressTrack, alpha).rgb)
            RenderUtils.drawRect(hpBarX, cy + 3f, hpBarX + hpBarW * hpRatio, cy + 7f,
                withAlpha(if (hpRatio > 0.5f) successCol else failureCol, alpha).rgb)

            rowIndex++
            if (rowIndex >= perCol) {
                rowIndex = 0
                colIndex++
                if (colIndex >= cols) return@forEach
            }
        }

        // 标题
        drawText(x0 + TAB_SIDE_PADDING, y0 + 5f, "TAB", separatorCol)
    }

    /* ============================= 尺寸计算 ============================= */

    private fun defaultRequiredWidth(): Float {
        var w = PADDING_X * 2 + 30f + 80f
        if (showFps) w += 48f
        if (showTime) w += 52f
        if (showCoords) w += 92f
        return w
    }

    private fun notifyRequiredWidth(): Float = PADDING_X * 2 + 26f + 12f + 180f + 20f

    private fun tabRequiredWidth(): Float = TAB_MIN_WIDTH

    private fun tabRequiredHeight(): Float {
        val players = mc.theWorld?.playerEntities ?: return islandHeight
        val rows = min(players.size, TAB_MAX_ROWS)
        val cols = if (rows > 10) 2 else 1
        val perCol = (rows + cols - 1) / cols
        return TAB_TOP_PADDING + perCol * TAB_ROW_HEIGHT + TAB_BOTTOM_PADDING
    }

    /* ============================= 绘制辅助 ============================= */

    private fun drawText(x: Float, y: Float, text: String, color: Color): Float {
        Fonts.minecraftFont.drawString(text, x, y, color.rgb)
        return x + Fonts.minecraftFont.getStringWidth(text)
    }

    private fun drawTextRight(right: Float, y: Float, text: String, color: Color): Float {
        val w = Fonts.minecraftFont.getStringWidth(text).toFloat()
        Fonts.minecraftFont.drawString(text, right - w, y, color.rgb)
        return right - w
    }

    private fun drawTextCentered(x: Float, y: Float, containerW: Float, text: String, color: Color) {
        val w = Fonts.minecraftFont.getStringWidth(text)
        Fonts.minecraftFont.drawString(text, x + (containerW - w) / 2f, y, color.rgb)
    }

    /** 画一个小圆角"芯片"+ 居中文字，返回下一个 x 坐标 */
    private fun drawChip(x: Float, centerY: Float, text: String, color: Color): Float {
        val font = Fonts.minecraftFont
        val tw = font.getStringWidth(text)
        val cw = tw + 12f
        val ch = 16f
        RenderUtils.drawRoundedRect(x, centerY - ch / 2f, x + cw, centerY + ch / 2f, color.rgb, ch / 2f)
        font.drawString(text, x + 6f, centerY - font.FONT_HEIGHT / 2f, Color.BLACK.rgb)
        return x + cw + 8f
    }

    private fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun blend(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * tt).roundToInt(),
            (a.green + (b.green - a.green) * tt).roundToInt(),
            (a.blue + (b.blue - a.blue) * tt).roundToInt(),
            (a.alpha + (b.alpha - a.alpha) * tt).roundToInt(),
        )
    }
}
