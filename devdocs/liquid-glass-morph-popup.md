# 共享形变弹窗

实现位于 `app/src/main/java/com/huangder/lumibooks/ui/components/`。

- `LiquidGlassMorphPopupHost`：页面与弹窗双插槽，采样页面内容，仅向弹窗提供折射背景。`active` 应保持到归位动画结束。
- `AnchoredLiquidGlassMorphPopup`：接收原控件的 root 坐标、点击位置和目标尺寸，统一处理边缘留白、锚点内缩和展开位置。
- `LiquidGlassMorphPopup`：底层矩形动画，统一处理玻璃材质、圆角、光边、文字淡入淡出、点击外部关闭、返回键和减少动态效果设置。
- `LiquidGlassMorphPopupStyle`：统一的起始圆角、目标圆角、屏幕留白和锚点内缩；默认依次为 8dp、24dp、8dp、2dp。
- `LiquidGlassMorphSelector` / `LiquidGlassMorphSelectorDefaults`：通用单选列表，统一标题、文字、选中颜色、间距与字号缩放；默认宽度 102dp。

## 接入

```kotlin
LiquidGlassMorphPopupHost(
    active = anchor != null,
    content = { PageContent() },
    popup = {
        anchor?.let { source ->
            AnchoredLiquidGlassMorphPopup(
                anchorBounds = source.boundsInRoot,
                originCenter = source.tapInRoot,
                width = LiquidGlassMorphSelectorDefaults.Width,
                height = LiquidGlassMorphSelectorDefaults.contentHeight(
                    LocalDensity.current.fontScale
                ),
                closeRequested = closing,
                onDismiss = { closing = true },
                onFinished = { anchor = null },
                startContent = { OriginalRow(returnValue) },
                content = { enabled ->
                    LiquidGlassMorphSelector(
                        title = title,
                        options = options,
                        selected = selected,
                        enabled = enabled && !saving,
                        onSelect = ::select,
                        optionLabel = { labelFor(it) }
                    )
                }
            )
        }
    }
)
```

`startContent` 应复用原控件的布局，并在收起时提供即将显示的最新值。原控件在弹窗存在期间保持布局占位并隐藏文字，收到 `onFinished` 后恢复显示。宿主的页面插槽应包含完整页面，弹窗插槽位于采样范围之外。

自定义内容可直接使用 `AnchoredLiquidGlassMorphPopup` 的 `content` 插槽；需要自定义目标矩形时使用底层 `LiquidGlassMorphPopup`。业务层负责选项数据、保存与错误反馈，共享组件负责外观、定位和动画。
