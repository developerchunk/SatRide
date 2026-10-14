import UIKit
import SwiftUI
import Shared

// MARK: Root

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

/// The app root: Compose owns the whole UI, and on iOS a native SwiftUI tab bar floats over it.
///
/// The tab bar is native because its Liquid Glass material has to sample the content behind it from
/// the native view hierarchy -- a `UIKitView` embedded inside Compose cannot see the Skiko-rendered
/// content. Compose still owns navigation; this bar only reflects the active tab (pushed from
/// `SatRideNavHost` via `MainViewControllerKt.setNativeTabObserver`) and routes taps back
/// (`MainViewControllerKt.selectNativeTab`).
struct ContentView: View {
    /// The active tab index, or `-1` when the current destination is not a tab. Driven by Compose.
    @State private var activeTab: Int = -1

    var body: some View {
        ZStack(alignment: .bottom) {
            ComposeView()
                .ignoresSafeArea()

            if activeTab >= 0 {
                LiquidGlassTabBar(activeTab: activeTab)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    // Scoped to the bar. Placing it on the ZStack made `ComposeView` a
                    // participant in every bar animation, so each show/hide asked UIKit to
                    // animate the entire Compose surface alongside it.
                    .animation(.spring(response: 0.35, dampingFraction: 0.85), value: activeTab >= 0)
            }
        }
        .onAppear {
            // Compose owns the source of truth; this only mirrors it. Replayed on register so the
            // bar does not flash unselected on the first frame.
            MainViewControllerKt.setNativeTabObserver { idx in
                // Kotlin `Int` bridges to Swift as a boxed `KotlinInt` (an `NSNumber`); pull the value
                // out via `int32Value`, then hop to `Int`.
                let index = Int(idx.int32Value)
                DispatchQueue.main.async { self.activeTab = index }
            }
        }
    }
}

// MARK: Tab bar

/// A floating, five-tab Liquid Glass pill with a glass lens the finger carries across it.
///
/// ## The lens
///
/// It is **clear glass, not a filled shape**. It carries no colour of its own: what you see inside
/// it is the bar and the icons underneath, refracted. That is the whole point of the material, and
/// it is what the first implementation got wrong — it drew an opaque accent-filled bubble, which
/// hid the very thing glass is supposed to reveal.
///
/// Two things about *when* and *where* it appears:
///
/// - **Only while a finger is down.** At rest the bar is just the bar, and the selected tab is
///   said by its accent tint alone. A lens parked on a tab reads as a decoration stuck to the UI;
///   a lens that appears under your finger reads as an object you picked up.
/// - **It follows the finger continuously, it does not step between tabs.** Snapping it to slots
///   made it jump from icon to icon, which reads as a selection indicator being moved rather than
///   as glass being slid across a surface. Glass does not teleport — it sits wherever the hand
///   puts it, including halfway between two icons. The tab that would be *selected* is still
///   whichever slot the finger is over, and is shown by the icon tint: the lens is where your hand
///   is, the tint is what happens if you let go.
///
/// The bar and the lens live in one ``GlassEffectContainer``. That is required, not decorative:
/// Liquid Glass cannot sample other Liquid Glass, so two independent `.glassEffect` views stacked
/// on each other render as two unrelated sheets. Inside a container SwiftUI blends them as one
/// material, so the lens appears to lift out of the bar rather than to land on top of it.
///
/// ## Why it feels instant
///
/// Three separate delays were removed.
///
/// 1. `DragGesture(minimumDistance: 0)` replaces `minimumDistance: 8`. The gesture now fires on
///    touch-down, so the capsule is already moving before the finger has travelled anywhere.
/// 2. The per-tab `Button`s are gone. A `Button` deliberately waits before committing, to tell a
///    tap from the start of a scroll, and it was competing with the drag gesture for the same
///    touch — so the *first* thing that happened on a tap was a pause.
/// 3. The highlight is driven by local state, not by `activeTab`. `activeTab` arrives from Compose
///    (tap → Kotlin → navigate → publish → Swift), and waiting for that round trip was visible.
///    The capsule moves immediately and Compose catches up; `onChange(of: activeTab)` re-syncs so
///    a tab change from anywhere else — a deep link, a notification — still lands correctly.
private struct LiquidGlassTabBar: View {
    /// The active tab index as Compose sees it. The source of truth, but not what is animated.
    let activeTab: Int

    private let labels: [String] = MainViewControllerKt.nativeTabLabels()
    /// The tracking accent (`SatRideTheme.accents.tracking` / sign-in's `LiquidBlue = 0xFF2F6BFF`).
    private let accent = Color(red: 0x2F / 255.0, green: 0x6B / 255.0, blue: 0xFF / 255.0)

    /// Inset between the pill's curved ends and the icon row. Also the origin the slot maths and
    /// the finger-to-index mapping both measure from, so they cannot drift apart.
    private static let edgeInset: CGFloat = 6

    @Namespace private var glassNamespace

    /// Measured bar bounds, so a finger x maps to a tab and the lens stays inside the pill.
    @State private var barSize: CGSize = .zero
    /// The tab the finger is currently over; `-1` when nothing is being touched.
    @State private var pressedIndex: Int = -1
    /// Where the finger is, in bar-local points. Only meaningful while `pressedIndex >= 0`.
    @State private var fingerX: CGFloat = 0
    /// What the bar believes is selected, ahead of Compose confirming it.
    @State private var localSelection: Int = -1

    /// The tab drawn as selected: the finger if there is one, else the local selection, else
    /// whatever Compose last said.
    private var highlighted: Int {
        if pressedIndex >= 0 { return pressedIndex }
        if localSelection >= 0 { return localSelection }
        return activeTab
    }

    private var slotWidth: CGFloat {
        guard barSize.width > 0, !labels.isEmpty else { return 0 }
        return (barSize.width - Self.edgeInset * 2) / CGFloat(labels.count)
    }

    /// The lens's leading edge: centred on the finger, kept inside the pill so it never hangs off
    /// the curved ends.
    private var lensX: CGFloat {
        min(max(fingerX - slotWidth / 2, 0), max(barSize.width - slotWidth, 0))
    }

    var body: some View {
        glassContainer {
            ZStack(alignment: .leading) {
                // The raw bar. Nothing is drawn on top of it opaquely, so the lens has something
                // real to refract.
                HStack(spacing: 0) {
                    ForEach(labels.indices, id: \.self) { index in
                        tab(index: index, label: labels[index], selected: index == highlighted)
                    }
                }
                .padding(.horizontal, Self.edgeInset)
                .padding(.vertical, 8)
                .modifier(GlassPillBackground())

                // The lens — present only while a finger is down, floating freely over the icons.
                if pressedIndex >= 0, slotWidth > 0 {
                    SelectionCapsule(namespace: glassNamespace)
                        .frame(width: slotWidth, height: max(barSize.height - 8, 0))
                        .offset(x: lensX)
                        .allowsHitTesting(false)
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
        }
        .background(SizeReadingView(size: $barSize))
        .contentShape(Capsule())
        .gesture(selectorGesture)
        // Interactive, because this tracks a finger. `interactiveSpring` is tuned for exactly
        // that: it settles fast enough to feel attached to the hand, without the overshoot a
        // presentation spring would add on every pointer event.
        .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.85), value: lensX)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: pressedIndex >= 0)
        .animation(.spring(response: 0.32, dampingFraction: 0.78), value: highlighted)
        .onAppear { localSelection = activeTab }
        .onChange(of: activeTab) { _, newValue in
            // Compose is authoritative. This matters for the navigations the bar did not start:
            // a `satride://` link, a notification tap, [Open in Sky] from a pass.
            if pressedIndex < 0 { localSelection = newValue }
        }
    }

    /// Touch-down lifts the lens under the finger. There is no slop threshold and no long press:
    /// the lens tracks the finger from the first event and follows it continuously across the bar.
    /// Lifting commits whichever tab it ended over and puts the lens away.
    private var selectorGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                fingerX = value.location.x
                let index = indexAt(value.location.x)
                guard index != pressedIndex else { return }
                // Seed the lens's position *before* it first appears, so it materialises under the
                // finger rather than sliding in from wherever the last gesture ended.
                pressedIndex = index
                UISelectionFeedbackGenerator().selectionChanged()
            }
            .onEnded { value in
                let target = indexAt(value.location.x)
                pressedIndex = -1
                // Set locally first so the tint settles on this frame; Compose confirms it a
                // round trip later, by which time the bar is already where it should be.
                localSelection = target
                MainViewControllerKt.selectNativeTab(index: Int32(target))
            }
    }

    /// Maps a finger x in bar-local space to a tab index, clamped to the tab count.
    private func indexAt(_ x: CGFloat) -> Int {
        guard slotWidth > 0 else { return 0 }
        let slot = Int((x - Self.edgeInset) / slotWidth)
        return min(max(slot, 0), labels.count - 1)
    }

    /// One tab: the icon and its label. Not a `Button` — see the type documentation.
    @ViewBuilder
    private func tab(index: Int, label: String, selected: Bool) -> some View {
        VStack(spacing: 4) {
            SatRideTabIcon(kind: index, tint: selected ? accent : .secondary)
                .frame(width: 26, height: 26)
                .scaleEffect(selected ? 1.12 : 1.0)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(selected ? accent : .secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }

    /// Groups the bar and the capsule into one Liquid Glass system on iOS 26+, and is a plain
    /// pass-through before it (where there is no glass to group).
    @ViewBuilder
    private func glassContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 0, content: content)
        } else {
            content()
        }
    }
}

/// The carried lens: clear, interactive Liquid Glass in a capsule.
///
/// The stable ``glassEffectID`` is what lets it *emerge from* the bar's own glass when it appears
/// and sink back into it when it goes, rather than fading in as an unrelated second sheet lying on
/// top. Without the ID — and without the surrounding ``GlassEffectContainer`` — that is exactly
/// what it would look like, because glass cannot sample glass.
///
/// `.clear` is the transparent variant — it refracts and brightens what is behind it without
/// laying down a frost of its own, which is what lets the bar and the selected icon read straight
/// through it. `.interactive()` adds the scale-and-shimmer response to touch that the system
/// controls have.
private struct SelectionCapsule: View {
    let namespace: Namespace.ID

    var body: some View {
        if #available(iOS 26.0, *) {
            Capsule()
                .fill(Color.clear)
                .glassEffect(.clear.interactive(), in: Capsule())
                .glassEffectID("satride.tab.selection", in: namespace)
        } else {
            // No Liquid Glass before 26: the nearest honest thing is a faint translucent capsule
            // with a rim, which still reads as "this one" without pretending to be a material.
            Capsule()
                .fill(Color.white.opacity(0.10))
                .overlay(Capsule().stroke(Color.white.opacity(0.22), lineWidth: 1))
        }
    }
}

/// Liquid Glass on iOS 26+; an ultra-thin material pill with a faint hairline on older OSes.
private struct GlassPillBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // Native Liquid Glass samples the ComposeView behind it. Exact symbol verified against
            // the iphoneos26.5 SDK; `.glassEffect(_:in:)` is the iOS-26 API.
            content.glassEffect(.regular, in: Capsule())
        } else {
            content
                .background(Capsule().fill(.ultraThinMaterial))
                .overlay(Capsule().stroke(Color.primary.opacity(0.08), lineWidth: 1))
        }
    }
}

/// Reports the host view's size through a binding via a clear `GeometryReader` background -- the
/// version-safe way to read a view's bounds (the bar needs its width to map a finger x to a tab).
private struct SizeReadingView: View {
    @Binding var size: CGSize

    var body: some View {
        GeometryReader { proxy in
            Color.clear.preference(key: BarSizeKey.self, value: proxy.size)
        }
        .onPreferenceChange(BarSizeKey.self) { size = $0 }
    }
}

private struct BarSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) { value = nextValue() }
}

// MARK: Icons — ported from SatRideIcons.kt (thin-stroke line art, drawn rather than imported)

/// One of the five tab icons, redrawn in SwiftUI `Canvas` to match the Compose set exactly.
/// `kind`: 0 Satellites, 1 Sky, 2 Play, 3 Passes, 4 Profile.
private struct SatRideTabIcon: View {
    let kind: Int
    var tint: Color

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            let lineWidth = min(w, h) * 0.075
            let stroke = StrokeStyle(lineWidth: lineWidth, lineCap: .round)
            switch kind {
            case 0: drawSatellites(context, w: w, h: h, tint: tint, stroke: stroke)
            case 1: drawSky(context, w: w, h: h, tint: tint, stroke: stroke)
            case 2: drawPlay(context, w: w, h: h, tint: tint, stroke: stroke)
            case 3: drawPasses(context, w: w, h: h, tint: tint, stroke: stroke)
            default: drawProfile(context, w: w, h: h, tint: tint, stroke: stroke)
            }
        }
    }

    private func dot(_ ctx: GraphicsContext, at p: CGPoint, r: CGFloat, tint: Color) {
        let rect = CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)
        ctx.fill(Path(ellipseIn: rect), with: .color(tint))
    }

    private func line(_ ctx: GraphicsContext, from a: CGPoint, to b: CGPoint, tint: Color, stroke: StrokeStyle) {
        var path = Path()
        path.move(to: a)
        path.addLine(to: b)
        ctx.stroke(path, with: .color(tint), style: stroke)
    }

    /// A stroked ellipse arc, optionally rotated (degrees, clockwise from 3 o'clock, matching Compose).
    private func arc(
        _ ctx: GraphicsContext, cx: CGFloat, cy: CGFloat, rx: CGFloat, ry: CGFloat,
        rotDeg: CGFloat, startDeg: CGFloat, sweepDeg: CGFloat, tint: Color, stroke: StrokeStyle,
    ) {
        let rot = rotDeg * .pi / 180
        let cr = cos(rot), sr = sin(rot)
        var path = Path()
        let steps = 48
        for i in 0...steps {
            let th = (startDeg + sweepDeg * CGFloat(i) / CGFloat(steps)) * .pi / 180
            let x0 = rx * cos(th)
            let y0 = ry * sin(th)
            let x = cx + x0 * cr - y0 * sr
            let y = cy + x0 * sr + y0 * cr
            if i == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        ctx.stroke(path, with: .color(tint), style: stroke)
    }

    // 0 — a list of objects.
    private func drawSatellites(_ ctx: GraphicsContext, w: CGFloat, h: CGFloat, tint: Color, stroke: StrokeStyle) {
        for fraction in [0.3, 0.5, 0.7] {
            let y = h * CGFloat(fraction)
            dot(ctx, at: CGPoint(x: w * 0.24, y: y), r: w * 0.055, tint: tint)
            line(ctx, from: CGPoint(x: w * 0.42, y: y), to: CGPoint(x: w * 0.78, y: y), tint: tint, stroke: stroke)
        }
    }

    // 1 — a small constellation.
    private func drawSky(_ ctx: GraphicsContext, w: CGFloat, h: CGFloat, tint: Color, stroke: StrokeStyle) {
        let a = CGPoint(x: w * 0.22, y: h * 0.72)
        let b = CGPoint(x: w * 0.46, y: h * 0.34)
        let c = CGPoint(x: w * 0.78, y: h * 0.52)
        line(ctx, from: a, to: b, tint: tint, stroke: stroke)
        line(ctx, from: b, to: c, tint: tint, stroke: stroke)
        dot(ctx, at: a, r: w * 0.075, tint: tint)
        dot(ctx, at: b, r: w * 0.10, tint: tint)
        dot(ctx, at: c, r: w * 0.075, tint: tint)
    }

    // 2 — a planet with a satellite on its orbit.
    private func drawPlay(_ ctx: GraphicsContext, w: CGFloat, h: CGFloat, tint: Color, stroke: StrokeStyle) {
        let center = CGPoint(x: w * 0.5, y: h * 0.52)
        let planetRect = CGRect(x: center.x - w * 0.17, y: center.y - w * 0.17, width: w * 0.34, height: w * 0.34)
        ctx.stroke(Path(ellipseIn: planetRect), with: .color(tint), style: stroke)
        arc(ctx, cx: center.x, cy: center.y, rx: w * 0.36, ry: h * 0.17, rotDeg: -24, startDeg: 28, sweepDeg: 305,
            tint: tint, stroke: stroke)
        dot(ctx, at: CGPoint(x: center.x + w * 0.36, y: center.y), r: w * 0.06, tint: tint)
    }

    // 3 — a pass: the arc a satellite traces above the horizon.
    private func drawPasses(_ ctx: GraphicsContext, w: CGFloat, h: CGFloat, tint: Color, stroke: StrokeStyle) {
        let thin = StrokeStyle(lineWidth: stroke.lineWidth * 0.8, lineCap: .round)
        arc(ctx, cx: w * 0.5, cy: h * 0.55, rx: w * 0.35, ry: h * 0.31, rotDeg: 0, startDeg: 180, sweepDeg: 180,
            tint: tint, stroke: stroke)
        line(ctx, from: CGPoint(x: w * 0.1, y: h * 0.78), to: CGPoint(x: w * 0.9, y: h * 0.78), tint: tint, stroke: thin)
        dot(ctx, at: CGPoint(x: w * 0.5, y: h * 0.28), r: w * 0.085, tint: tint)
    }

    // 4 — a head and shoulders.
    private func drawProfile(_ ctx: GraphicsContext, w: CGFloat, h: CGFloat, tint: Color, stroke: StrokeStyle) {
        let head = CGPoint(x: w * 0.5, y: h * 0.34)
        let headRect = CGRect(x: head.x - w * 0.19, y: head.y - w * 0.19, width: w * 0.38, height: w * 0.38)
        ctx.stroke(Path(ellipseIn: headRect), with: .color(tint), style: stroke)
        arc(ctx, cx: w * 0.5, cy: h * 0.87, rx: w * 0.32, ry: h * 0.31, rotDeg: 0, startDeg: 200, sweepDeg: 140,
            tint: tint, stroke: stroke)
    }
}
