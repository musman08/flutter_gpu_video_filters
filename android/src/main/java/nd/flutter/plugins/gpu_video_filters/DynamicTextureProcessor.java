package nd.flutter.plugins.gpu_video_filters;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;

import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.opengles.GL10;
@UnstableApi
class DynamicTextureShaderProgram extends BaseGlShaderProgram {
    GlProgram glProgram;
    private final int[] textures;
    final Map<String, Bitmap> externalBitmaps;

    private boolean released = false;
    public DynamicTextureShaderProgram(String vertexShader, String fragmentShader,
                                Map<String, Bitmap> externalBitmaps,
                                Map<String, Float> currentFloats,
                                Map<String, float[]> currentArrayFloats,
                                boolean useHdr) throws VideoFrameProcessingException {
        super(useHdr, 1 + (externalBitmaps != null ? externalBitmaps.size() : 0));
        this.externalBitmaps = externalBitmaps;
        this.textures = new int[externalBitmaps != null ? externalBitmaps.size() : 0];
        try {
            glProgram = new GlProgram(vertexShader, fragmentShader);;
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        // Draw the frame on the entire normalized device coordinate space, from -1 to 1, for x and y.
        glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        for (String key : currentFloats.keySet()) {
            glProgram.setFloatUniform(key, checkNotNull(currentFloats.get(key)));
        }
        for (String key : currentArrayFloats.keySet()) {
            glProgram.setFloatsUniform(key, checkNotNull(currentArrayFloats.get(key)));
        }

        if (externalBitmaps != null && !externalBitmaps.isEmpty()) {
            if (vertexShader.contains("aTexCoords")) {
                glProgram.setBufferAttribute(
                        "aTexCoords",
                        GlUtil.getTextureCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            }
            GLES20.glGenTextures(textures.length, textures, 0);
            int i = 0;
            for (Map.Entry<String, Bitmap> entry : externalBitmaps.entrySet()) {
                GLES20.glBindTexture(GL10.GL_TEXTURE_2D, textures[i]);
                GLES20.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST);
                GLES20.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
                GLES20.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_REPEAT);
                GLES20.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_REPEAT);
                if (entry.getValue() != null) {
                    GLUtils.texImage2D(GL10.GL_TEXTURE_2D, /* level= */ 0, entry.getValue(), /* border= */ 0);
                } else {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                }
                i++;
            }
        }
    }

    @NonNull
    @Override
    public Size configure(int inputWidth, int inputHeight) {
        return new Size(inputWidth, inputHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
        try {
            if (externalBitmaps != null && !externalBitmaps.isEmpty()) {
                int i = 0;
                for (Map.Entry<String, Bitmap> entry : externalBitmaps.entrySet()) {
                    GLES20.glBindTexture(GL10.GL_TEXTURE_2D, textures[i]);
                    if (entry.getValue() != null) {
                        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, /* level= */ 0, entry.getValue(), /* border= */ 0);
                    }
                    GlUtil.checkGlError();
                    i++;
                }
            }
            checkStateNotNull(glProgram).use();

            GlUtil.checkGlError();
            glProgram.setSamplerTexIdUniform("inputImageTexture", inputTexId, /* texUnitIndex= */ 0);
            if (externalBitmaps != null && !externalBitmaps.isEmpty()) {
                int i = 0;
                for (Map.Entry<String, Bitmap> entry : externalBitmaps.entrySet()) {
                    glProgram.setSamplerTexIdUniform(entry.getKey(), textures[i], /* texUnitIndex= */ i + 1);
                    i++;
                }
            }
            glProgram.bindAttributesAndUniforms();
            // The four-vertex triangle strip forms a quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
            GlUtil.checkGlError();
        } catch (GlUtil.GlException e) {
            Log.e(getClass().getSimpleName(), "drawFrame", e);
            throw new VideoFrameProcessingException(e, presentationTimeUs);
        }
    }

    @Override
    public void release() throws VideoFrameProcessingException {
        if (released) {
            return;
        }
        super.release();
        try {
            glProgram.delete();
            released = true;
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }
}

@UnstableApi
public class DynamicTextureProcessor {
    private final String vertexShader;
    private final String fragmentShader;
    private final Map<String, Bitmap> externalBitmaps = new HashMap<>();
    public OnUniformsUpdater onUniformsUpdater;

    private DynamicTextureShaderProgram textureEffect;
    DynamicTextureProcessor(
            String vertexShader, String fragmentShader,
            Map<String, Float> fragmentDefaultFloats,
            Map<String, float[]> fragmentDefaultArrayFloats,
            java.util.List<String> textureNames) {
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
        if (textureNames != null) {
            for (String name : textureNames) {
                this.externalBitmaps.put(name, null);
            }
        }
        for (String key : fragmentDefaultFloats.keySet()) {
            currentFloats.put(key, fragmentDefaultFloats.get(key));
        }
        for (String key : fragmentDefaultArrayFloats.keySet()) {
            float[] values = checkNotNull(fragmentDefaultArrayFloats.get(key));
            currentArrayFloats.put(key, values);
        }
    }

    DynamicTextureShaderProgram create(boolean useHdr) throws VideoFrameProcessingException {
        textureEffect = new DynamicTextureShaderProgram(vertexShader, fragmentShader, externalBitmaps, currentFloats, currentArrayFloats, useHdr);
        return textureEffect;
    }

    DynamicTextureShaderProgram createComposition(boolean useHdr) throws VideoFrameProcessingException {
        return new DynamicTextureShaderProgram(vertexShader, fragmentShader, externalBitmaps, currentFloats, currentArrayFloats, useHdr);
    }

    private final Map<String, Float> currentFloats = new HashMap<>();
    private final Map<String, float[]> currentArrayFloats = new HashMap<>();

    public void setFloatUniform(String name, float value) {
        currentFloats.put(name, value);
        if (textureEffect != null && textureEffect.glProgram != null) {
            textureEffect.glProgram.setFloatUniform(name, value);
        }
        if (onUniformsUpdater != null) {
            onUniformsUpdater.setFloatUniform(name, value);
        }
    }

    public void setFloatsUniform(String name, float[] value) {
        currentArrayFloats.put(name, value);
        if (textureEffect != null && textureEffect.glProgram != null) {
            textureEffect.glProgram.setFloatsUniform(name, value);
        }
        if (onUniformsUpdater != null) {
            onUniformsUpdater.setFloatsUniform(name, value);
        }
    }

    public void setBitmap(String name, Bitmap value) {
        this.externalBitmaps.put(name, value);
        if (textureEffect != null && textureEffect.glProgram != null) {
            textureEffect.externalBitmaps.put(name, value);
        }
        if (onUniformsUpdater != null) {
            onUniformsUpdater.setBitmapUniform(name, value);
        }
    }

    public void dispose() throws VideoFrameProcessingException {
        if (textureEffect != null) {
            textureEffect.release();
            textureEffect = null;
        }
    }
}
