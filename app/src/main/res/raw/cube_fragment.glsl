#version 300 es
precision mediump float;
in vec3 vColor;
in vec2 vUv;
in vec3 vInk;
in float vGlyph;
uniform sampler2D uAtlas;
in vec2 vLocal;
in float vSticker;
uniform float uDim;
uniform float uHighlight;
uniform float uPulse;
uniform float uPxPerDp;
out vec4 outColor;
void main() {
    // Per-axis screen gradient length, so a diagonal edge gets the same dp width as an upright one.
    // Taken outside any branch: derivatives are undefined in non-uniform control flow.
    vec2 dx = dFdx(vLocal);
    vec2 dy = dFdy(vLocal);
    vec3 color = mix(vColor, vInk, texture(uAtlas, vUv).a * vGlyph);
    if (vSticker > 0.5) {
        color *= mix(1.0, 0.4, uDim);
        if (uHighlight > 0.5) {
            vec3 highlight = vec3(167.0, 151.0, 255.0) / 255.0;
            color = mix(color, highlight, 0.55 * clamp(uPulse, 0.0, 1.0));
            vec2 edge = (vec2(1.0) - abs(vLocal)) / max(sqrt(dx * dx + dy * dy), vec2(0.00001));
            float distanceDp = min(edge.x, edge.y) / uPxPerDp;
            if (distanceDp < 3.0) color = highlight;
            else if (distanceDp < 4.0) color = vec3(18.0, 20.0, 23.0) / 255.0;
        }
    }
    outColor = vec4(color, 1.0);
}
