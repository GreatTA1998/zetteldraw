package com.zetteldraw.penpoc;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSurfaceView;

/** Surface that exists but is never valid, so TouchHelper stays idle under Robolectric. */
@Implements(SurfaceView.class)
public class IdleSurfaceViewShadow extends ShadowSurfaceView {
    private final SurfaceHolder holder = new ShadowSurfaceView.FakeSurfaceHolder() {
        private Surface surface;

        @Override
        public Surface getSurface() {
            if (surface == null) {
                try {
                    surface = Surface.class.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return surface;
        }
    };

    @Implementation
    @Override
    protected SurfaceHolder getHolder() {
        return holder;
    }
}
