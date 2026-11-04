#version 300 es
uniform mat4 uViewProj;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec2 aUv;
layout(location = 4) in vec3 aInk;
layout(location = 5) in float aGlyph;
out vec2 vUv;
out vec3 vInk;
out float vGlyph;
out vec3 vColor;
void main() {
    vec3 n = normalize(mat3(uModel) * aNormal);
    float light = 0.8 + 0.2 * max(dot(n, normalize(vec3(0.4, 0.8, 0.6))), 0.0);
    vColor = aColor * light;
    vInk = aInk * light;
    vUv = aUv;
    vGlyph = aGlyph;
    gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
}
