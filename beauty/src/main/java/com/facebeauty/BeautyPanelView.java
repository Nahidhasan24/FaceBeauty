package com.facebeauty;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ready-made effect selector (TikTok/Snapchat style): category tabs, round effect buttons, one
 * strength slider (two-way for reshape items like chin length), colour swatches, reset and
 * hold-to-compare. Built entirely from {@link BeautyCatalog}; no resources needed.
 *
 * <pre>
 * panel.setParams(BeautyParams.natural());
 * panel.setListener(p -&gt; beautyView.setParams(p));            // or videoProcessor.setParams(p)
 * panel.setCompareListener(on -&gt; beautyView.setParams(on ? BeautyParams.none() : panel.getParams()));
 * </pre>
 */
public class BeautyPanelView extends LinearLayout {

    public interface Listener {
        /** Called on every change with the panel's live params (copy them if you keep them). */
        void onParamsChanged(BeautyParams params);
    }

    public interface CompareListener {
        void onCompare(boolean showOriginal);
    }

    private static final int ACCENT = 0xFFFF4F8B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xB3FFFFFF;
    private static final int BUTTON_BG = 0x33FFFFFF;

    private final BeautyParams params = BeautyParams.natural();
    private final BeautyParams defaults = BeautyParams.natural();
    private Listener listener;
    private CompareListener compareListener;
    private BeautyCatalog.Category category = BeautyCatalog.Category.LOOKS;
    private BeautyCatalog.Item selected;
    private boolean updatingSlider;

    private final LinearLayout sliderRow;
    private final TextView sliderLabel;
    private final SeekBar seek;
    private final TextView sliderValue;
    private final LinearLayout palette;
    private final LinearLayout itemRow;
    private final List<TextView> tabs = new ArrayList<>();
    private final List<View> itemCircles = new ArrayList<>();
    private final List<TextView> itemLabels = new ArrayList<>();
    private List<BeautyCatalog.Item> items = new ArrayList<>();

    public BeautyPanelView(Context context) {
        this(context, null);
    }

    public BeautyPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setBackgroundColor(0xE6121216);
        setPadding(0, dp(8), 0, dp(8));
        setClickable(true); // don't let taps fall through to the preview

        // Strength slider.
        sliderRow = new LinearLayout(context);
        sliderRow.setOrientation(HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(dp(16), 0, dp(12), 0);
        sliderLabel = text(context, 13, TEXT);
        sliderRow.addView(sliderLabel, new LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        seek = new SeekBar(context);
        sliderRow.addView(seek, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sliderValue = text(context, 13, TEXT);
        sliderValue.setGravity(Gravity.END);
        sliderRow.addView(sliderValue, new LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sliderRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (updatingSlider || selected == null) return;
                float v = selected.bipolar ? (progress - 100) / 100f : progress / 100f;
                if (selected.isLook() && v <= 0f) v = 0.01f; // keep the filter selected
                selected.set(params, v);
                showValue(v);
                refreshItems();
                notifyChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        // Colour swatches.
        palette = new LinearLayout(context);
        palette.setOrientation(HORIZONTAL);
        palette.setGravity(Gravity.CENTER);
        addView(palette, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        // Effect buttons.
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        itemRow = new LinearLayout(context);
        itemRow.setOrientation(HORIZONTAL);
        itemRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        scroll.addView(itemRow);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Tabs + compare + reset.
        LinearLayout tabRow = new LinearLayout(context);
        tabRow.setOrientation(HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(dp(8), dp(6), dp(8), 0);
        TextView compare = pill(context, "◐");
        tabRow.addView(compare, new LayoutParams(dp(40), dp(32)));
        for (BeautyCatalog.Category c : BeautyCatalog.Category.values()) {
            TextView tab = text(context, 14, TEXT_DIM);
            tab.setText(c.label);
            tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(v -> selectCategory(c));
            tabs.add(tab);
            tabRow.addView(tab, new LayoutParams(0, dp(32), 1f));
        }
        TextView reset = pill(context, "↺");
        reset.setOnClickListener(v -> reset());
        tabRow.addView(reset, new LayoutParams(dp(40), dp(32)));
        addView(tabRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setupCompare(compare);

        selectCategory(BeautyCatalog.Category.LOOKS);
    }

    // ------------------------------------------------------------ API

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Press-and-hold the ◐ button to see the original. */
    public void setCompareListener(CompareListener listener) {
        this.compareListener = listener;
    }

    /** Current selection. Do not keep a reference across changes; copy it. */
    public BeautyParams getParams() {
        return params;
    }

    public void setParams(BeautyParams p) {
        params.set(p);
        refreshAll();
    }

    /** What the ↺ button restores (default: {@link BeautyParams#natural()}). */
    public void setDefaults(BeautyParams p) {
        defaults.set(p);
    }

    public void selectCategory(BeautyCatalog.Category c) {
        category = c;
        items = BeautyCatalog.items(c);
        selected = null;
        if (c == BeautyCatalog.Category.LOOKS) {
            for (BeautyCatalog.Item i : items) if (i.isSelectedLook(params) && i.look != null) selected = i;
        }
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            boolean on = BeautyCatalog.Category.values()[i] == c;
            t.setTextColor(on ? TEXT : TEXT_DIM);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        buildItems();
        refreshAll();
    }

    // ------------------------------------------------------------ UI

    private void buildItems() {
        itemRow.removeAllViews();
        itemCircles.clear();
        itemLabels.clear();
        Context ctx = getContext();
        for (BeautyCatalog.Item item : items) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView circle = text(ctx, 22, TEXT);
            circle.setGravity(Gravity.CENTER);
            circle.setText(item.isLook() ? (item.look == null ? "⊘" : "") : item.icon);
            cell.addView(circle, new LayoutParams(dp(54), dp(54)));
            TextView label = text(ctx, 11, TEXT_DIM);
            label.setText(item.label);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            cell.addView(label, new LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(v -> onItemTapped(item));
            itemRow.addView(cell, new LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT));
            itemCircles.add(circle);
            itemLabels.add(label);
        }
    }

    private void onItemTapped(BeautyCatalog.Item item) {
        selected = item;
        if (item.isLook()) {
            if (!item.isSelectedLook(params)) item.set(params, item.defaultValue);
            if (item.look == null) selected = null;
            notifyChanged();
        } else if (!item.bipolar && item.get(params) <= 0.005f) {
            // First tap switches the effect on at a pleasant default; the slider fine-tunes it.
            item.set(params, item.defaultValue);
            notifyChanged();
        }
        refreshAll();
    }

    private void refreshAll() {
        refreshItems();
        refreshSlider();
        refreshPalette();
    }

    private void refreshItems() {
        for (int i = 0; i < items.size() && i < itemCircles.size(); i++) {
            BeautyCatalog.Item item = items.get(i);
            boolean isSelected = item == selected
                    || (item.isLook() && item.isSelectedLook(params) && (selected == null || selected.isLook()));
            boolean active = item.isActive(params);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(item.isLook() ? item.previewColor()
                    : item.hasColors() && active ? withAlpha(item.getColor(params), 0xCC) : BUTTON_BG);
            if (isSelected) bg.setStroke(dp(3), ACCENT);
            else if (active) bg.setStroke(dp(1), 0x99FFFFFF);
            itemCircles.get(i).setBackground(bg);
            TextView label = itemLabels.get(i);
            label.setTextColor(isSelected ? ACCENT : active ? TEXT : TEXT_DIM);
        }
    }

    private void refreshSlider() {
        boolean show = selected != null && !(selected.isLook() && selected.look == null);
        sliderRow.setVisibility(show ? VISIBLE : INVISIBLE);
        if (!show) return;
        float v = selected.get(params);
        updatingSlider = true;
        seek.setMax(selected.bipolar ? 200 : 100);
        seek.setProgress(Math.round(selected.bipolar ? v * 100f + 100f : v * 100f));
        updatingSlider = false;
        sliderLabel.setText(selected.label);
        showValue(v);
    }

    private void showValue(float v) {
        int pct = Math.round(v * 100f);
        sliderValue.setText(selected != null && selected.bipolar && pct > 0
                ? String.format(Locale.US, "+%d", pct) : String.valueOf(pct));
    }

    private void refreshPalette() {
        palette.removeAllViews();
        boolean show = selected != null && selected.hasColors();
        palette.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        int current = selected.getColor(params);
        for (int color : selected.colors) {
            View sw = new View(getContext());
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            d.setStroke(dp(color == current ? 3 : 1), color == current ? TEXT : 0x66FFFFFF);
            sw.setBackground(d);
            LayoutParams lp = new LayoutParams(dp(28), dp(28));
            lp.setMarginStart(dp(6));
            lp.setMarginEnd(dp(6));
            palette.addView(sw, lp);
            BeautyCatalog.Item item = selected;
            sw.setOnClickListener(v -> {
                item.setColor(params, color);
                if (item.get(params) <= 0.005f) item.set(params, item.defaultValue);
                refreshAll();
                notifyChanged();
            });
        }
    }

    private void reset() {
        params.set(defaults);
        if (selected != null && selected.isLook()) selected = null;
        selectCategory(category);
        notifyChanged();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupCompare(View compare) {
        compare.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (compareListener != null) {
                if (a == MotionEvent.ACTION_DOWN) compareListener.onCompare(true);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) compareListener.onCompare(false);
            }
            return true;
        });
    }

    private void notifyChanged() {
        if (listener != null) listener.onParamsChanged(params);
    }

    private TextView text(Context ctx, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView pill(Context ctx, String label) {
        TextView t = text(ctx, 16, TEXT);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(BUTTON_BG);
        t.setBackground(d);
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
