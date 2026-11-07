package dev.leanhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Paixão TV home, on the concept's fixed 1920 x 1080 canvas: positions below are design pixels. */
public final class MainActivity extends Activity {
    private static final String NAME = BuildConfig.OWNER_NAME;
    private static final String STREMIO = "com.stremio.one", NETFLIX = "com.netflix.ninja";
    private static final String PRIME = "com.amazon.firebat";
    private static final List<String> PINNED = Arrays.asList(
            "com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.netflix.ninja",
            "com.amazon.firebat", STREMIO);
    private static final String APPSTORE = "com.amazon.venezia";
    private static final ComponentName SETTINGS = new ComponentName(
            "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity");
    private static final int DOCK_APPS = 5;
    private static final long CLOSE_AFTER_MS = 4_000;
    private static final int LILAC = 0xFF9C9CFF, SOFT = 0xCCFFFFFF, GLASS = 0x1FFFFFFF, GLASS_LINE = 0x2EFFFFFF;

    private FrameLayout root, panel;
    private LinearLayout dock;
    private GridLayout grid;
    private ImageView scene;
    private TextView context, hello, support, weatherTemp, weatherText;
    private View weatherCard, ring, status;
    private Weather.Icon weatherIcon;
    private int sceneRes;
    private float scale;
    private Typeface regular, medium, semibold;
    private List<String> dockApps = new ArrayList<>();
    private final List<View> greetingViews = new ArrayList<>();
    private final Map<String, LinearLayout> shelves = new HashMap<>();
    private final Map<String, String> shelfContent = new HashMap<>();
    private final Map<String, Integer> dockCards = new HashMap<>();
    private final Map<String, Integer> shelfEntries = new HashMap<>();
    private List<Trakt.Item> netflixContinue = new ArrayList<>();
    private List<Trakt.Row> netflixPopular = new ArrayList<>();
    private final Map<View, Trakt.Item> menuItems = new WeakHashMap<>();
    private final Handler handler = new Handler();
    private final Runnable closeBackground = this::closeApps;
    private FrameLayout menu;
    private View menuOpener;
    private boolean weatherReady;
    private String focusedApp;

    private int px(float design) {
        return Math.round(design * scale);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        regular = getResources().getFont(R.font.outfit_regular);
        medium = getResources().getFont(R.font.outfit_medium);
        semibold = getResources().getFont(R.font.outfit_semibold);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1021);
        scene = new ImageView(this);
        scene.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(scene, new FrameLayout.LayoutParams(-1, -1));

        header();
        greeting();
        weather();
        apps();
        panel();
        setContentView(root);
    }

    private void header() {
        View dot = circle(LILAC);
        place(dot, 133, 97, 14, 14);
        place(label("PAIXÃO TV", 29, Color.WHITE, medium), 164, 83, -2, -2);
        place(label("SEU ESPAÇO", 14, SOFT, regular), 165, 119, -2, -2);

        LinearLayout clocks = new LinearLayout(this);
        clocks.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMM");
        date.setFormat12Hour("EEEE, d MMM");
        date.setAllCaps(true);
        style(date, 20, SOFT, regular);
        TextClock hour = new TextClock(this);
        hour.setFormat24Hour("HH:mm");
        hour.setFormat12Hour("HH:mm");
        style(hour, 41, Color.WHITE, semibold);
        hour.setPadding(px(24), 0, px(24), 0);
        clocks.addView(date);
        clocks.addView(hour);
        status = circle(0xFF8EE3B0);
        clocks.addView(status, new LinearLayout.LayoutParams(px(10), px(10)));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        corner.setMargins(0, px(76), px(120), 0);
        root.addView(clocks, corner);
    }

    private void greeting() {
        context = label("", 21, SOFT, regular);
        place(context, 146, 270, -2, -2);
        hello = label("", 84, Color.WHITE, medium);
        place(hello, 142, 314, -2, -2);
        TextView name = label(NAME + ".", 84, Color.WHITE, semibold);
        place(name, 142, 407, -2, -2);
        support = label("", 26, SOFT, regular);
        place(support, 146, 531, -2, -2);
        greetingViews.addAll(Arrays.asList(context, hello, name, support));
    }

    /** The focused app's rows take the place of the greeting and the weather. */
    private void showShelf(String pkg) {
        boolean any = false;
        for (Map.Entry<String, LinearLayout> shelf : shelves.entrySet()) {
            boolean match = shelf.getKey().equals(pkg);
            shelf.getValue().setVisibility(match ? View.VISIBLE : View.GONE);
            any |= match;
        }
        for (View view : greetingViews) view.setVisibility(any ? View.GONE : View.VISIBLE);
        weatherCard.setVisibility(weatherReady && !any ? View.VISIBLE : View.INVISIBLE);
    }

    /** Builds an app's rows, leaving them alone when nothing changed so focus and images stay put. */
    private void setRows(String pkg, List<Trakt.Row> rows) {
        StringBuilder content = new StringBuilder();
        List<Trakt.Row> filled = new ArrayList<>();
        for (Trakt.Row row : rows) {
            if (row.items.isEmpty()) continue;
            filled.add(row);
            for (Trakt.Item item : row.items) {
                content.append(item.link).append(item.progress).append(item.title).append(item.detail);
            }
        }
        if (filled.isEmpty() || content.toString().equals(shelfContent.get(pkg))) return;
        shelfContent.put(pkg, content.toString());

        LinearLayout shelf = shelves.get(pkg);
        boolean hadFocus = shelf != null && shelf.findFocus() != null;
        if (shelf == null) {
            shelf = new LinearLayout(this);
            shelf.setOrientation(LinearLayout.VERTICAL);
            shelf.setClipChildren(false);
            shelf.setVisibility(View.GONE);
            shelves.put(pkg, shelf);
            root.addView(shelf, root.indexOfChild(ring), frame(142, 158, 1636, 530));
        }
        shelf.removeAllViews();
        int lastFirstCard = View.NO_ID, firstCard = View.NO_ID;
        for (int i = 0; i < filled.size(); i++) {
            Trakt.Row row = filled.get(i);
            TextView title = label(row.title.toUpperCase(), 20, Color.WHITE, semibold);
            title.setPadding(px(6), 0, 0, 0);
            shelf.addView(title);
            LinearLayout cards = new LinearLayout(this);
            cards.setClipChildren(false);
            boolean last = i == filled.size() - 1;
            for (Trakt.Item item : row.items) cards.addView(watchCard(pkg, item, last));
            int entry = cards.getChildAt(0).findViewWithTag("card").getId();
            if (firstCard == View.NO_ID) firstCard = entry;
            lastFirstCard = entry;
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(px(6), px(8), px(6), px(6));
            scroll.setFocusable(false);
            scroll.addView(cards);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.bottomMargin = last ? 0 : px(12);
            shelf.addView(scroll, gap);
        }
        shelfEntries.put(pkg, lastFirstCard);
        linkDockToShelf();
        showShelf(focusedApp);
        if (hadFocus) shelf.findViewById(firstCard).requestFocus();
    }

    /** Continue Watching over what is popular, or films over series when nothing is in progress. */
    private void showNetflix() {
        if (netflixContinue.isEmpty()) {
            setRows(NETFLIX, netflixPopular);
        } else {
            setRows(NETFLIX, Arrays.asList(new Trakt.Row("Continuar assistindo", netflixContinue),
                    Tmdb.mix("Em alta na Netflix", netflixPopular)));
        }
    }

    private void linkDockToShelf() {
        for (Map.Entry<String, Integer> entry : shelfEntries.entrySet()) {
            Integer card = dockCards.get(entry.getKey());
            View view = card == null ? null : dock.findViewById(card);
            if (view != null) view.setNextFocusUpId(entry.getValue());
        }
    }

    private View watchCard(String pkg, Trakt.Item item, boolean lastRow) {
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xFF1F2B45);
        plate.setCornerRadius(px(16));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);

        FrameLayout card = new FrameLayout(this);
        card.setTag("card");
        card.setId(View.generateViewId());
        card.setBackground(plate);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(item.title);
        addTo(card, image, 0, 0, 256, 144);
        if (item.progress >= 0) {
            View track = new View(this);
            track.setBackgroundColor(0x66000000);
            addTo(card, track, 0, 138, 256, 6);
            View played = new View(this);
            played.setBackgroundColor(LILAC);
            addTo(card, played, 0, 138, 256 * item.progress / 100f, 6);
        }
        if (item.image != null) Trakt.image(this, item.image, px(256), image::setImageBitmap);

        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(16));
        outline.setStroke(px(4), Color.WHITE);
        card.setOnFocusChangeListener((v, focused) -> {
            card.setForeground(focused ? outline : null);
            if (focused) ring.animate().alpha(0f).setDuration(80).start();
        });
        card.setOnClickListener(v -> openItem(pkg, item));
        if (item.traktId > 0) menuItems.put(card, item);
        if (lastRow) {
            Integer dockCard = dockCards.get(pkg);
            if (dockCard != null) card.setNextFocusDownId(dockCard);
        }

        TextView title = line(item.title, 18, Color.WHITE, semibold);
        TextView detail = line(item.detail, 15, SOFT, regular);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(px(256), px(144));
        cardSize.bottomMargin = px(8);
        column.addView(card, cardSize);
        column.addView(title, new LinearLayout.LayoutParams(px(256), -2));
        LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(px(256), -2);
        detailSize.topMargin = px(3);
        column.addView(detail, detailSize);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(px(256), -2);
        spacing.rightMargin = px(20);
        column.setLayoutParams(spacing);
        return column;
    }

    private TextView line(String value, float size, int colour, Typeface face) {
        TextView view = label(value, size, colour, face);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setShadowLayer(px(6), 0, px(1), 0xB3000000);
        return view;
    }

    private void openItem(String pkg, Trakt.Item item) {
        Intent intent = new Intent(Intent.ACTION_VIEW, item.uri()).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (NETFLIX.equals(pkg)) {
            String id = Tmdb.netflixId(this, item);
            if (id == null) {
                open(launchIntent(pkg));
                return;
            }
            // The flags and "source" are what Netflix's TV app needs to honour a title link.
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + id))
                    .setComponent(new ComponentName(pkg, pkg + ".MainActivity")).setFlags(0x10808000)
                    .putExtra("source", "30");
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException failed) {
            open(launchIntent(pkg));
        }
    }

    private void weather() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(glass(28));
        addTo(card, label(BuildConfig.WEATHER_CITY, 18, SOFT, medium), 30, 23, -2, -2);
        weatherTemp = label("", 78, Color.WHITE, medium);
        addTo(card, weatherTemp, 29, 59, -2, -2);
        weatherText = label("", 18, SOFT, regular);
        addTo(card, weatherText, 32, 157, -2, -2);
        weatherIcon = new Weather.Icon(this);
        addTo(card, weatherIcon, 226, 42, 100, 100);
        card.setVisibility(View.INVISIBLE);
        weatherCard = card;
        place(card, 1446, 288, 354, 206);
    }

    private void apps() {
        place(label("SEUS APLICATIVOS", 20, Color.WHITE, semibold), 154, 696, -2, -2);
        TextView hint = label("Escolha um app para começar", 19, SOFT, regular);
        FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        right.setMargins(0, px(697), px(154), 0);
        root.addView(hint, right);

        View surface = new View(this);
        surface.setBackground(glass(40));
        place(surface, 121, 753, 1678, 224);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        place(dock, 154, 775, 1612, 184);

        ring = new ImageView(this);
        ((ImageView) ring).setImageBitmap(ringBitmap());
        ring.setAlpha(0f);
        place(ring, 0, 0, 265, 182);

        place(label("◉ OK PARA ABRIR     ← → NAVEGAR     ☰ OPÇÕES", 17, SOFT, regular), 152, 1008, -2, -2);
    }

    /** The focus outline and its glow, drawn once and moved between tiles rather than redrawn on each. */
    private Bitmap ringBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(px(265), px(182), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        RectF card = new RectF(px(30), px(30), px(235), px(152));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x75C4D4FF);
        paint.setMaskFilter(new BlurMaskFilter(px(20), BlurMaskFilter.Blur.NORMAL));
        canvas.drawRoundRect(new RectF(card.left - 4, card.top - 4, card.right + 4, card.bottom + 4),
                px(28), px(28), paint);
        paint.setMaskFilter(null);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        canvas.drawRoundRect(card, px(25), px(25), paint);
        paint.setXfermode(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(px(4));
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(card.left + 2, card.top + 2, card.right - 2, card.bottom - 2),
                px(24), px(24), paint);
        return bitmap;
    }

    private void panel() {
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xFF0B1021);
        panel.setVisibility(View.GONE);
        addTo(panel, label("Todos os apps", 52, Color.WHITE, medium), 142, 84, -2, -2);
        grid = new GridLayout(this);
        grid.setColumnCount(6);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(10), px(10), px(10), px(10));
        scroll.addView(grid);
        addTo(panel, scroll, 142, 180, 1636, 860);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService.homeShown();
        showTimeOfDay();
        refreshDock();
        handler.postDelayed(closeBackground, CLOSE_AFTER_MS);
        Weather.load(this, this::showWeather);
        Trakt.load(this, this::showTrakt);
        Tmdb.load(this, all -> {
            setRows(PRIME, all.get(PRIME));
            netflixPopular = all.get(NETFLIX);
            showNetflix();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(closeBackground);
    }

    /** Nothing but Home stays in memory. The wait lets an app that just closed save its progress. */
    private void closeApps() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        List<String> apps = new ArrayList<>(PINNED);
        apps.add(APPSTORE);
        apps.addAll(sideloaded().values());
        for (String pkg : apps) manager.killBackgroundProcesses(pkg);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && menu == null) {
            Trakt.Item item = menuItems.get(getCurrentFocus());
            if (item != null) {
                menuOpener = getCurrentFocus();
                showMenu(menuOpener, item);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** ☰ on a Continue Watching card: the same choices as Trakt's site, saved there. */
    private void showMenu(View card, Trakt.Item item) {
        boolean series = item.episode != null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xFF161E33);
        plate.setCornerRadius(px(28));
        plate.setStroke(px(1.5f), GLASS_LINE);
        box.setBackground(plate);
        box.setPadding(px(48), px(36), px(48), px(36));
        TextView title = label(item.title, 34, Color.WHITE, semibold);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView detail = label(item.detail, 19, SOFT, regular);
        detail.setPadding(0, px(8), 0, px(24));
        box.addView(detail);

        menu = new FrameLayout(this);
        menu.setBackgroundColor(0xB3000000);
        menu.setClickable(true);
        List<View> options = new ArrayList<>();
        options.add(option(series ? "Já assisti este episódio" : "Já assisti", () -> {
            closeMenu(card);
            save(item, true);
        }));
        options.add(option(series ? "Abandonar série" : "Remover do Continuar Assistindo", () -> {
            closeMenu(card);
            save(item, false);
        }));
        options.add(option("Cancelar", () -> closeMenu(card)));
        for (int i = 0; i < options.size(); i++) {
            View view = options.get(i);
            view.setNextFocusLeftId(view.getId());
            view.setNextFocusRightId(view.getId());
            view.setNextFocusUpId(options.get(Math.max(0, i - 1)).getId());
            view.setNextFocusDownId(options.get(Math.min(options.size() - 1, i + 1)).getId());
            box.addView(view);
        }
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(px(640), -2, Gravity.CENTER);
        menu.addView(box, center);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        options.get(0).requestFocus();
    }

    private View option(String text, Runnable action) {
        TextView view = label(text, 22, Color.WHITE, medium);
        view.setId(View.generateViewId());
        view.setGravity(Gravity.CENTER);
        view.setPadding(px(24), px(16), px(24), px(16));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(GLASS);
        shape.setCornerRadius(px(18));
        view.setBackground(shape);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnFocusChangeListener((v, focused) -> {
            shape.setColor(focused ? Color.WHITE : GLASS);
            view.setTextColor(focused ? Color.BLACK : Color.WHITE);
        });
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = px(10);
        view.setLayoutParams(size);
        return view;
    }

    private void closeMenu(View card) {
        root.removeView(menu);
        menu = null;
        card.requestFocus();
    }

    private void save(Trakt.Item item, boolean watched) {
        Trakt.change(this, item, watched, saved -> {
            Toast.makeText(this, saved ? "Salvo no Trakt" : "Não consegui salvar no Trakt", Toast.LENGTH_SHORT).show();
            if (saved) Trakt.load(this, this::showTrakt);
        });
    }

    private void showTrakt(List<Trakt.Row> rows) {
        setRows(STREMIO, rows);
        netflixContinue = Trakt.netflixContinue(this);
        showNetflix();
    }

    @Override
    public void onBackPressed() {
        if (menu != null) {
            closeMenu(menuOpener);
            return;
        }
        if (panel.getVisibility() == View.VISIBLE) {
            closePanel();
            return;
        }
        Integer card = dockCards.get(focusedApp); // Back leaves the rows for the dock.
        if (card != null && dock.findFocus() == null) dock.findViewById(card).requestFocus();
    }

    private void showTimeOfDay() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        boolean morning = hour >= 5 && hour < 12, afternoon = hour >= 12 && hour < 18;
        int res = morning ? R.drawable.scene_morning : afternoon ? R.drawable.scene_afternoon : R.drawable.scene_night;
        if (res != sceneRes) {
            sceneRes = res;
            scene.setImageResource(res);
        }
        String weekday = new java.text.SimpleDateFormat("EEEE").format(now.getTime()).split("-")[0].toUpperCase();
        int day = now.get(Calendar.DAY_OF_WEEK);
        String your = day == Calendar.SATURDAY || day == Calendar.SUNDAY ? "SEU " : "SUA ";
        context.setText(morning ? "APROVEITE A SUA MANHÃ" : afternoon ? "APROVEITE A SUA TARDE"
                : your + weekday + " À NOITE");
        hello.setText(morning ? "Bom dia," : afternoon ? "Boa tarde," : "Boa noite,");
        support.setText(morning ? "Comece o dia no seu ritmo." : afternoon ? "Seu entretenimento, no seu ritmo."
                : "Um bom filme começa aqui.");
    }

    private void showWeather(Weather weather) {
        weatherTemp.setText(weather.temperature + "°");
        weatherText.setText(weather.condition());
        weatherIcon.show(weather);
        weatherReady = true;
        showShelf(focusedApp);
    }

    /** Rebuilds only when the apps changed, so coming back from an app keeps the focus where it was. */
    private void refreshDock() {
        List<String> now = new ArrayList<>();
        for (String pkg : PINNED) if (launchIntent(pkg) != null) now.add(pkg);
        for (String pkg : sideloaded().values()) if (now.size() < DOCK_APPS) now.add(pkg);
        if (now.equals(dockApps) && dock.getChildCount() > 0) return;
        dockApps = now;
        dock.removeAllViews();
        View start = null;
        for (String pkg : now) {
            View slot = appSlot(pkg, true);
            dock.addView(slot);
            if (STREMIO.equals(pkg)) start = slot;
            else if (start == null) start = slot;
        }
        View all = slot("Todos os apps", new AppsIcon(), 0xFF1F2B45, null, this::openPanel, true);
        dock.addView(all);
        (start != null ? start : all).findViewWithTag("card").requestFocus();
        linkDockToShelf();
    }

    private TreeMap<String, String> sideloaded() {
        PackageManager pm = getPackageManager();
        TreeMap<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                ApplicationInfo app = info.activityInfo.applicationInfo;
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (PINNED.contains(app.packageName) || getPackageName().equals(app.packageName)) continue;
                found.put(app.loadLabel(pm).toString(), app.packageName);
            }
        }
        return found;
    }

    private void openPanel() {
        grid.removeAllViews();
        List<String> all = new ArrayList<>();
        for (String pkg : PINNED) if (launchIntent(pkg) != null) all.add(pkg);
        all.addAll(sideloaded().values());
        if (launchIntent(APPSTORE) != null) all.add(APPSTORE);
        for (String pkg : all) grid.addView(appSlot(pkg, false));
        grid.addView(slot("Configurações", getDrawable(android.R.drawable.ic_menu_preferences), 0xFF1F2B45, null,
                () -> open(new Intent().setComponent(SETTINGS)), false));
        panel.setVisibility(View.VISIBLE);
        ring.setAlpha(0f);
        grid.getChildAt(0).findViewWithTag("card").requestFocus();
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        View all = dock.getChildAt(dock.getChildCount() - 1);
        all.findViewWithTag("card").requestFocus();
    }

    private View appSlot(String pkg, boolean inDock) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            Drawable banner = app.loadBanner(pm);
            Drawable art = banner != null ? banner : app.loadIcon(pm);
            return slot(app.loadLabel(pm).toString(), art, cornerColour(art), pkg, () -> {
                closeQuietly();
                open(launchIntent(pkg));
            }, inDock);
        } catch (PackageManager.NameNotFoundException gone) {
            return new View(this);
        }
    }

    private void closeQuietly() {
        panel.setVisibility(View.GONE);
    }

    /** A 205 x 122 card with its name below, in a 222 x 177 slot. */
    private View slot(String name, Drawable art, int plateColour, String pkg, Runnable action, boolean inDock) {
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(plateColour);
        plate.setCornerRadius(px(25));
        ImageView image = new ImageView(this);
        image.setImageDrawable(art);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);

        FrameLayout card = new FrameLayout(this);
        card.setTag("card");
        card.setBackground(plate);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(name);
        card.setId(View.generateViewId());
        if (inDock && pkg != null) dockCards.put(pkg, card.getId());
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView label = label(name, 19, SOFT, regular);
        label.setGravity(Gravity.CENTER);
        card.setOnClickListener(v -> action.run());

        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(25));
        outline.setStroke(px(4), Color.WHITE);
        card.setOnFocusChangeListener((v, focused) -> {
            label.setTypeface(focused ? semibold : regular);
            label.setTextColor(focused ? Color.WHITE : SOFT);
            if (inDock) {
                if (focused) {
                    focusedApp = pkg;
                    showShelf(pkg);
                    card.post(() -> moveRing(card));
                }
            } else {
                card.setForeground(focused ? outline : null);
            }
        });

        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        slot.setGravity(Gravity.CENTER_HORIZONTAL);
        slot.setPadding(px(8), px(9), px(8), 0);
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(px(205), px(122));
        cardSize.bottomMargin = px(13);
        slot.addView(card, cardSize);
        slot.addView(label, new LinearLayout.LayoutParams(-1, -2));
        slot.setLayoutParams(inDock ? new LinearLayout.LayoutParams(px(240), px(177))
                : new GridLayout.LayoutParams(new android.view.ViewGroup.MarginLayoutParams(px(240), px(190))));
        return slot;
    }

    private void moveRing(View card) {
        Rect bounds = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, bounds);
        float x = bounds.left - px(30), y = bounds.top - px(30);
        if (ring.getAlpha() < 1f) {
            ring.animate().cancel();
            ring.setTranslationX(x);
            ring.setTranslationY(y);
            ring.setAlpha(1f);
        } else {
            ring.animate().translationX(x).translationY(y).setDuration(130).start();
        }
    }

    private static int cornerColour(Drawable art) {
        Bitmap small = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        art.setBounds(0, 0, 32, 18);
        art.draw(new Canvas(small));
        int colour = small.getPixel(1, 1);
        small.recycle();
        return Color.alpha(colour) < 128 ? 0xFF1F2B45 : colour | 0xFF000000;
    }

    private GradientDrawable glass(float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(GLASS);
        shape.setCornerRadius(px(radius));
        shape.setStroke(Math.max(1, px(1.5f)), GLASS_LINE);
        return shape;
    }

    private View circle(int colour) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(colour);
        View view = new View(this);
        view.setBackground(dot);
        return view;
    }

    private TextView label(String value, float size, int colour, Typeface face) {
        TextView view = new TextView(this);
        view.setText(value);
        style(view, size, colour, face);
        return view;
    }

    private void style(TextView view, float size, int colour, Typeface face) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setTextColor(colour);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
    }

    private void place(View view, float x, float y, float w, float h) {
        addTo(root, view, x, y, w, h);
    }

    private void addTo(FrameLayout parent, View view, float x, float y, float w, float h) {
        parent.addView(view, frame(x, y, w, h));
    }

    private FrameLayout.LayoutParams frame(float x, float y, float w, float h) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h));
        params.leftMargin = px(x);
        params.topMargin = px(y);
        return params;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException | NullPointerException failed) {
            Toast.makeText(this, "Não abriu", Toast.LENGTH_SHORT).show();
        }
    }

    /** The concept's 3 x 3 pastel grid for "Todos os apps". */
    private final class AppsIcon extends Drawable {
        private final int[] colours = {0xFFF2F0FF, 0xFFC9D4FF, 0xFFF7C9E6, 0xFFC9D4FF, 0xFFD8F2DC, 0xFFD9CCFF,
                0xFFF7DDB0, 0xFFD8F2DC, 0xFFC9D4FF};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas canvas) {
            Rect box = getBounds();
            float cell = px(22), gap = px(5), side = cell * 3 + gap * 2;
            float left = box.exactCenterX() - side / 2, top = box.exactCenterY() - side / 2;
            for (int i = 0; i < 9; i++) {
                float x = left + (i % 3) * (cell + gap), y = top + (i / 3) * (cell + gap);
                paint.setColor(colours[i]);
                canvas.drawRoundRect(x, y, x + cell, y + cell, px(6), px(6), paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
