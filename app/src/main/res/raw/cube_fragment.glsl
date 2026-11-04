#version 300 es
precision mediump float;
in vec3 vColor;
in vec2 vUv;
in vec3 vInk;
in float vGlyph;
uniform sampler2D uAtlas;
out vec4 outColor;
void main() {
    outColor = vec4(mix(vColor, vInk, texture(uAtlas, vUv).a * vGlyph), 1.0);
}
