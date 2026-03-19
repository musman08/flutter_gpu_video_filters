precision mediump float;

// Primary input coordinates
varying vec2 textureCoordinate;
varying vec2 textureCoordinate2;

// --- UNIFORMS ---

// Samplers (3 Total)
uniform sampler2D inputImageTexture; // 0. Primary Video Frame (Media3 inputTexId)
uniform sampler2D inputTextureCubeData; // 1. Lookup Table (LUT)
uniform lowp sampler2D inputImageTexture2; // 2. Secondary Overlay Texture

// Color Processing Uniforms (from the first block)
uniform highp float inputExposure;
uniform lowp float inputContrast;
uniform lowp float inputSaturation;
uniform lowp float inputTemperature;
uniform lowp float inputTint;
uniform lowp float inputIntensity; // Intensity for the LUT application

// --- CONSTANTS ---
const mediump vec3 luminanceWeighting = vec3(0.2125, 0.7154, 0.0721);
const lowp vec3 warmFilter = vec3(0.93, 0.54, 0.0);
const mediump mat3 RGBtoYIQ = mat3(0.299, 0.587, 0.114, 0.596, -0.274, -0.322, 0.212, -0.523, 0.311);
const mediump mat3 YIQtoRGB = mat3(1.0, 0.956, 0.621, 1.0, -0.272, -0.647, 1.0, -1.105, 1.702);

// --- HELPER FUNCTIONS ---

vec4 lookupFrom2DTexture(vec3 textureColor) {
    mediump float blueColor = textureColor.b * 63.0;
    mediump vec2 quad1;
    quad1.y = floor(floor(blueColor) / 8.0);
    quad1.x = floor(blueColor) - (quad1.y * 8.0);
    mediump vec2 quad2;
    quad2.y = floor(ceil(blueColor) / 8.0);
    quad2.x = ceil(blueColor) - (quad2.y * 8.0);
    mediump vec2 texPos1;
    texPos1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
    texPos1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
    mediump vec2 texPos2;
    texPos2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
    texPos2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
    mediump vec4 newColor1 = texture2D(inputTextureCubeData, texPos1);
    mediump vec4 newColor2 = texture2D(inputTextureCubeData, texPos2);
    return mix(newColor1, newColor2, fract(blueColor));
}

vec4 processColor0(vec4 sourceColor){
    return vec4(sourceColor.rgb * pow(2.0, inputExposure), sourceColor.w);
}
vec4 processColor1(vec4 sourceColor){
    return vec4(((sourceColor.rgb - vec3(0.5)) * inputContrast + vec3(0.5)), sourceColor.w);
}
vec4 processColor2(vec4 sourceColor){
    lowp float luminance = dot(sourceColor.rgb, luminanceWeighting);
    lowp vec3 greyScaleColor = vec3(luminance);
    return vec4(mix(greyScaleColor, sourceColor.rgb, inputSaturation), sourceColor.w);
}
vec4 processColor3(vec4 sourceColor){
    mediump vec3 yiq = RGBtoYIQ * sourceColor.rgb;//adjusting inputTint
    yiq.b = clamp(yiq.b + inputTint*0.5226*0.1, -0.5226, 0.5226);
    lowp vec3 rgb = YIQtoRGB * yiq;
    lowp vec3 processed = vec3(
    (rgb.r < 0.5 ? (2.0 * rgb.r * warmFilter.r) : (1.0 - 2.0 * (1.0 - rgb.r) * (1.0 - warmFilter.r))), //adjusting inputTemperature
    (rgb.g < 0.5 ? (2.0 * rgb.g * warmFilter.g) : (1.0 - 2.0 * (1.0 - rgb.g) * (1.0 - warmFilter.g))),
    (rgb.b < 0.5 ? (2.0 * rgb.b * warmFilter.b) : (1.0 - 2.0 * (1.0 - rgb.b) * (1.0 - warmFilter.b))));
    return vec4(mix(rgb, processed, inputTemperature), sourceColor.a);
}
vec4 processColor4(vec4 sourceColor){
   vec4 newColor = lookupFrom2DTexture(clamp(sourceColor.rgb, 0.0, 1.0));
   return mix(sourceColor, vec4(newColor.rgb, sourceColor.w), inputIntensity);
}

// --- MAIN EXECUTION ---
void main(){
    // 1. Fetch the primary video frame color
    vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);

    // 2. Apply the Color Adjustment Pipeline (Exposure, Contrast, Saturation, Temp/Tint)
    vec4 processedColor = processColor0(textureColor);
    processedColor = processColor1(processedColor);
    processedColor = processColor2(processedColor);
    processedColor = processColor3(processedColor);

    // 3. Apply the Lookup Table (LUT) effect
    vec4 finalProcessedColor = processColor4(processedColor);

    // 4. Apply the Overlay Mix (from the second shader)
    // Fetch the overlay texture color
    lowp vec4 overlayColor = texture2D(inputImageTexture2, textureCoordinate2);

    // Mix the fully processed video (finalProcessedColor) with the overlay,
    // using the overlay's alpha channel (overlayColor.a) as the blending factor.
    gl_FragColor = mix(finalProcessedColor, overlayColor, overlayColor.a);
}
