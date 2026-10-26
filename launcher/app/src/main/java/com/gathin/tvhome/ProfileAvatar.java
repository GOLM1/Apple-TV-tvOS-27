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
import java.io.File;

/** A personal photo stored beside the theme, never bundled into the APK. */
final class ProfileAvatar {
    static Drawable load(Context ctx, int size) {
        File directory = Config.dir(ctx);
        File photo = directory == null ? null : new File(directory, "profile.png");
        Bitmap bitmap = null;
        if (photo != null && photo.isFile()) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= size
                    && bounds.outHeight / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
            bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
        }
        if (bitmap == null) return ctx.getDrawable(R.drawable.ic_profile);
        ShapeDrawable avatar = new ShapeDrawable(new OvalShape());
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
