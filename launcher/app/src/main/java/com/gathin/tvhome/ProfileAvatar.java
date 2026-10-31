package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;

/** The active profile's small, offline portrait; never restores legacy personal photos. */
final class ProfileAvatar {
    static Drawable load(Context ctx, int size) {
        try { return load(ctx, Profiles.active(ctx), size); }
        catch (RuntimeException invalid) { return ctx.getDrawable(R.drawable.ic_profile); }
    }
    static Drawable load(Context ctx, ProfileList.Profile profile, int size) {
        if (profile.avatar.isEmpty()) return ctx.getDrawable(R.drawable.ic_profile);
        java.io.File file = Profiles.avatarFile(ctx, profile.id, profile.avatar);
        if (!file.isFile() || file.length() > 128 * 1024) return ctx.getDrawable(R.drawable.ic_profile);
        Bitmap bitmap = null;
        if (size > 0) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= size
                    && bounds.outHeight / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
        return round(ctx, bitmap, size);
    }
    static Drawable round(Context ctx, Bitmap bitmap, int size) {
        if (bitmap == null) return ctx.getDrawable(R.drawable.ic_profile);
        ShapeDrawable avatar = new ShapeDrawable(new OvalShape());
        avatar.setIntrinsicWidth(size);
        avatar.setIntrinsicHeight(size);
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max((float) size / bitmap.getWidth(), (float) size / bitmap.getHeight());
        Matrix transform = new Matrix();
        transform.setScale(scale, scale);
        transform.postTranslate((size - bitmap.getWidth() * scale) / 2f, (size - bitmap.getHeight() * scale) / 2f);
        shader.setLocalMatrix(transform);
        avatar.getPaint().setShader(shader);
        avatar.getPaint().setAntiAlias(true);
        avatar.getPaint().setFilterBitmap(true);
        return avatar;
    }
}
