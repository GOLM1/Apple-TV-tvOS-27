package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.UUID;

/** Remote-first profile switcher; selected portraits are local and the gallery loads on demand. */
public final class ProfilesActivity extends Activity {
    private List<ProfileList.Profile> profiles;
    private boolean manage;
    private boolean animations, reducedMotion, firstShow = true;
    private String focusedProfile;
    private TextView profileAction;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(this);
        animations = config.animations;
        reducedMotion = config.reducedMotion;
        manage = state == null ? getIntent().getBooleanExtra("manage", false) : state.getBoolean("manage");
        focusedProfile = state == null ? Profiles.activeId(this) : state.getString("focused");
        show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("manage", manage);
        state.putString("focused", focusedProfile);
        super.onSaveInstanceState(state);
    }
    private void show() {
        try { profiles = Profiles.list(this); }
        catch (RuntimeException invalid) {
            new AlertDialog.Builder(this).setMessage(R.string.profiles_read_error)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.profile_screen_bg);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setClipChildren(false);
        page.setPadding(dp(38), dp(32), dp(38), dp(32));
        scroll.addView(page);
        TextView heading = text(getString(manage ? R.string.profiles_manage : R.string.profiles_who), 40);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setIncludeFontPadding(false);
        page.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = text(getString(manage ? R.string.profile_manage_hint : R.string.profile_choose_hint), 17);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(0xFFA5AAB5);
        hint.setPadding(0, dp(12), 0, dp(28));
        page.addView(hint);
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setFillViewport(true);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setGravity(Gravity.CENTER);
        cards.setClipChildren(false);
        cards.setPadding(dp(14), dp(18), dp(14), dp(12));
        strip.addView(cards);
        page.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View selected = null, activeCard = null;
        String activeId = Profiles.activeId(this);
        for (ProfileList.Profile profile : profiles) {
            boolean active = profile.id.equals(activeId);
            View card = profileOption(profile, active);
            cards.addView(card, new LinearLayout.LayoutParams(dp(154), ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setContentDescription(getString(manage ? R.string.profile_edit_named : R.string.profile_switch_named, profile.name));
            card.setOnClickListener(v -> {
                if (manage) edit(profile);
                else if (Profiles.select(this, profile.id)) finish();
                else failed();
            });
            card.setOnLongClickListener(v -> { edit(profile); return true; });
            if (profile.id.equals(focusedProfile)) selected = card;
            if (active) activeCard = card;
        }
        if (profiles.size() < ProfileList.MAX) {
            View add = profileOption(null, false);
            add.setOnClickListener(v -> add());
            cards.addView(add, new LinearLayout.LayoutParams(dp(154), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        profileAction = text(getString(manage ? R.string.profile_done : R.string.profiles_manage), 16);
        profileAction.setId(View.generateViewId());
        profileAction.setGravity(Gravity.CENTER);
        profileAction.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        profileAction.setFocusable(true);
        profileAction.setClickable(true);
        profileAction.setDefaultFocusHighlightEnabled(false);
        StateListDrawable pill = new StateListDrawable();
        pill.addState(new int[]{android.R.attr.state_focused}, oval(0xFFF2F3F5, dp(23)));
        pill.addState(new int[]{}, oval(0x1FFFFFFF, dp(23)));
        profileAction.setBackground(pill);
        profileAction.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF171A22, 0xFFE2E5EA}));
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(dp(224), dp(46));
        actionLayout.gravity = Gravity.CENTER_HORIZONTAL;
        actionLayout.topMargin = dp(20);
        page.addView(profileAction, actionLayout);
        profileAction.setOnClickListener(v -> { manage = !manage; show(); });
        TextView footnote = text(getString(R.string.profile_hold_to_edit), 12);
        footnote.setGravity(Gravity.CENTER);
        footnote.setTextColor(0xFF7D8593);
        footnote.setPadding(0, dp(18), 0, 0);
        page.addView(footnote);
        for (int i = 0; i < cards.getChildCount(); i++) cards.getChildAt(i).setNextFocusDownId(profileAction.getId());
        setContentView(scroll);
        View target = selected == null ? activeCard : selected;
        if (target != null) {
            target.requestFocus();
            profileAction.setNextFocusUpId(target.getId());
        }
        if (firstShow && animations) {
            page.setAlpha(0f);
            page.setTranslationY(reducedMotion ? 0f : dp(8));
            page.animate().alpha(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 320)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start();
        }
        firstShow = false;
    }
    private View profileOption(ProfileList.Profile profile, boolean active) {
        LinearLayout tile = new LinearLayout(this);
        tile.setId(View.generateViewId());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClipChildren(false);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setNextFocusUpId(tile.getId());
        FrameLayout orb = new FrameLayout(this);
        orb.setClipChildren(false);
        tile.addView(orb, new LinearLayout.LayoutParams(dp(136), dp(136)));
        if (profile == null) {
            TextView plus = text("+", 46);
            plus.setGravity(Gravity.CENTER);
            plus.setIncludeFontPadding(false);
            plus.setBackground(oval(0xFF252C38, dp(60)));
            orb.addView(plus, new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER));
            tile.setContentDescription(getString(R.string.profile_add));
        } else {
            ImageView image = new ImageView(this);
            image.setImageDrawable(ProfileAvatar.load(this, profile, dp(120)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            orb.addView(image, new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER));
        }
        View ring = new View(this);
        GradientDrawable outline = oval(Color.TRANSPARENT, dp(68));
        outline.setStroke(dp(2), Color.WHITE);
        ring.setBackground(outline);
        ring.setAlpha(active ? 0.28f : 0f);
        ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        orb.addView(ring, new FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER));
        if (profile != null && (active || manage)) {
            ImageView badge = new ImageView(this);
            badge.setImageResource(manage ? R.drawable.ic_profile_edit : R.drawable.ic_profile_check);
            badge.setBackground(oval(0xFFE9EBEF, dp(13)));
            badge.setPadding(dp(5), dp(5), dp(5), dp(5));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.END);
            badgeLayout.setMarginEnd(dp(3));
            badgeLayout.bottomMargin = dp(3);
            orb.addView(badge, badgeLayout);
        }
        TextView name = text(profile == null ? getString(R.string.profile_add) : profile.name, 18);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(3), dp(15), dp(3), 0);
        name.setAlpha(active ? 0.9f : 0.65f);
        tile.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        Motion.Focus focus = new Motion.Focus(orb, false);
        tile.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                if (profile != null) focusedProfile = profile.id;
                if (profileAction != null) profileAction.setNextFocusUpId(tile.getId());
            }
            ring.animate().cancel();
            name.animate().cancel();
            float resting = active ? 0.28f : 0f;
            if (animations && !reducedMotion) focus.to(focused ? 1.08f : 1f, focused ? -dp(3) : 0, 0f);
            else { orb.setScaleX(1f); orb.setScaleY(1f); orb.setTranslationY(0f); }
            long duration = animations ? reducedMotion ? Motion.REDUCED_MS : 140 : 0;
            ring.animate().alpha(focused ? 1f : resting).setDuration(duration).start();
            name.animate().alpha(focused ? 1f : active ? 0.9f : 0.65f).setDuration(duration).start();
        });
        return tile;
    }
    private GradientDrawable oval(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }
    private void add() {
        nameDialog(getString(R.string.profile_add), "", name -> {
            List<ProfileList.Profile> next = new java.util.ArrayList<>(profiles);
            focusedProfile = UUID.randomUUID().toString().replace("-", "");
            next.add(new ProfileList.Profile(focusedProfile, name, ""));
            if (Profiles.save(this, next)) { manage = true; show(); edit(next.get(next.size() - 1)); }
            else failed();
        });
    }
    private void edit(ProfileList.Profile profile) {
        String[] options = {getString(R.string.profile_rename), getString(R.string.profile_picture),
                getString(R.string.profile_switch_named, profile.name), getString(R.string.profile_delete)};
        new AlertDialog.Builder(this).setTitle(profile.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) nameDialog(getString(R.string.profile_rename), profile.name,
                            name -> replace(profile, name, profile.avatar));
                    else if (which == 1) gallery(profile);
                    else if (which == 2) { if (Profiles.select(this, profile.id)) finish(); else failed(); }
                    else if (ProfileList.DEFAULT.equals(profile.id)) {
                        Toast.makeText(this, R.string.profile_keep_default, Toast.LENGTH_LONG).show();
                    } else new AlertDialog.Builder(this).setTitle(R.string.profile_delete)
                            .setMessage(getString(R.string.profile_delete_confirm, profile.name))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.profile_delete, (d, w) -> remove(profile)).show();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void remove(ProfileList.Profile profile) {
        // Only remove the catalogue entry after successfully switching away from it.
        if (profile.id.equals(Profiles.activeId(this)) && !Profiles.select(this, ProfileList.DEFAULT)) { failed(); return; }
        List<ProfileList.Profile> next = new java.util.ArrayList<>(profiles);
        next.remove(profile);
        if (Profiles.save(this, next)) {
            deleteSharedPreferences(Profiles.settingsName(profile.id));
            java.io.File directory = new java.io.File(new java.io.File(getFilesDir(), "profiles"), profile.id);
            // Exact owned filenames only; never recurse through storage or touch the original profile.
            for (String name : new String[]{"order.txt", "order.txt.bak", "library_apps.txt", "library_apps.txt.bak",
                    "library_home_removed.txt", "library_home_removed.txt.bak"}) new java.io.File(directory, name).delete();
            java.io.File[] photos = directory.listFiles(f -> f.isFile() && f.getName().startsWith("avatar-")
                    && (f.getName().endsWith(".webp") || f.getName().endsWith(".webp.bak")));
            if (photos != null) for (java.io.File photo : photos) photo.delete();
            directory.delete();
            show();
        } else failed();
    }
    private void replace(ProfileList.Profile profile, String name, String avatar) {
        List<ProfileList.Profile> next = new java.util.ArrayList<>(profiles);
        next.set(next.indexOf(profile), new ProfileList.Profile(profile.id, name, avatar));
        if (Profiles.save(this, next)) show(); else failed();
    }
    private interface Name { void ready(String value); }
    private void nameDialog(String title, String value, Name done) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!ProfileList.validName(name)) { input.setError(getString(R.string.profile_invalid_name)); return; }
            dialog.dismiss();
            done.ready(name);
        }));
        dialog.show();
        input.requestFocus();
    }
    private void gallery(ProfileList.Profile profile) {
        try {
            java.util.LinkedHashMap<String, List<AvatarCatalog.Entry>> groups = new java.util.LinkedHashMap<>();
            for (AvatarCatalog.Entry entry : ProfilePictures.catalogue(this))
                groups.computeIfAbsent(entry.group, key -> new java.util.ArrayList<>()).add(entry);
            String[] labels = groups.keySet().toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle(R.string.profile_picture)
                    .setItems(labels, (d, which) -> galleryPage(profile, groups.get(labels[which]), 0))
                    .setNeutralButton(R.string.profile_generic, (d, w) -> {
                        if (Profiles.picture(this, profile, "", null)) show(); else failed();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        } catch (Exception unreadable) { failed(); }
    }
    private void galleryPage(ProfileList.Profile profile, List<AvatarCatalog.Entry> entries, int page) {
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        content.setOrientation(LinearLayout.VERTICAL);
        int first = page * 6;
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entries.get(0).group + " · " + (page + 1) + "/" + ((entries.size() + 5) / 6))
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        LinearLayout row = null;
        for (int i = first; i < Math.min(first + 6, entries.size()); i++) {
            if ((i - first) % 3 == 0) { row = new LinearLayout(this); content.addView(row); }
            AvatarCatalog.Entry entry = entries.get(i);
            LinearLayout tile = card(entry.name, 64);
            row.addView(tile, spaced(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(v -> Toast.makeText(this, R.string.profile_image_loading, Toast.LENGTH_SHORT).show());
            ProfilePictures.load(this, entry, () -> current.get() && !isFinishing() && !isDestroyed(), bitmap -> {
                if (bitmap == null) {
                    tile.setOnClickListener(v -> Toast.makeText(this, R.string.profile_image_error, Toast.LENGTH_LONG).show());
                    return;
                }
                ((ImageView) tile.getChildAt(0)).setImageDrawable(ProfileAvatar.round(this, bitmap, dp(64)));
                tile.setOnClickListener(v -> {
                    ImageView preview = new ImageView(this);
                    preview.setImageDrawable(ProfileAvatar.round(this, bitmap, dp(128)));
                    preview.setPadding(dp(20), dp(12), dp(20), dp(12));
                    preview.setMinimumHeight(dp(160));
                    new AlertDialog.Builder(this).setTitle(entry.name).setView(preview)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.profile_use_picture, (d, w) -> {
                                if (Profiles.picture(this, profile, entry.id, bitmap)) { dialog.dismiss(); show(); }
                                else failed();
                            }).show();
                });
            });
        }
        LinearLayout navigation = new LinearLayout(this);
        content.addView(navigation);
        if (page > 0) button(navigation, R.string.profile_previous, () -> { dialog.dismiss(); galleryPage(profile, entries, page - 1); });
        if (first + 6 < entries.size()) button(navigation, R.string.cc_next, () -> { dialog.dismiss(); galleryPage(profile, entries, page + 1); });
        TextView credit = text(getString(R.string.profile_gallery_credit), 11);
        credit.setTextColor(0xFFB5BAC3);
        content.addView(credit);
        dialog.setOnDismissListener(d -> current.set(false));
        dialog.show();
        dialog.getWindow().setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout card(String label, int size) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(12), dp(10), dp(12));
        tile.setBackgroundResource(R.drawable.profile_choice_bg);
        tile.setFocusable(true);
        tile.setClickable(true);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.ic_profile);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(image, new LinearLayout.LayoutParams(dp(size), dp(size)));
        TextView name = text(label, 15);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, 0);
        tile.addView(name);
        tile.setContentDescription(label);
        return tile;
    }
    private void button(LinearLayout page, int label, Runnable action) {
        TextView button = text(getString(label), 18);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackgroundResource(R.drawable.profile_choice_bg);
        button.setFocusable(true);
        button.setClickable(true);
        LinearLayout.LayoutParams layout = page.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, dp(42), 1f)
                : spaced(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        page.addView(button, layout);
        button.setOnClickListener(v -> action.run());
    }
    private LinearLayout.LayoutParams spaced(int width, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMargins(dp(4), dp(5), dp(4), dp(5));
        return lp;
    }
    private TextView text(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void failed() { Toast.makeText(this, R.string.profiles_save_error, Toast.LENGTH_LONG).show(); }
}
