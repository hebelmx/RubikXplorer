# Cube session: what to do

**How it works:** you handle the cubes and the devices. I run the computer side: logs, screenshots and scoring.
You never need to type a command. Just tell me in chat when you start and finish each part.

**You need:** the Lenovo tablet, the Galaxy S24 FE, the **Rubik's cube (with logo)**, the **GAN cube (no stickers)**, and a white light (a LED or ceiling panel).

---

## Before every scan: mix the cube the same way

I need to know exactly how the cube was mixed, so I can check whether the app read it right.

1. Start with the cube **solved**.
2. Hold it with **white on top** and **green facing you**.
3. Do these 8 turns: **`L' F' D2 B D F2 D' U2`**
   - R = turn the right side · L = left · U = top · D = bottom · F = front · B = back
   - A letter alone = quarter turn clockwise (as if you were looking at that side) · **'** = counter-clockwise · **2** = half turn
4. If you make a mistake, just say "**bad mix**". It's no problem.

---

## Part 1: Quick Scan (about 40 min)

**Why:** this decides whether Quick Scan ships as normal (GA) or marked "Beta".

1. Say "**start part 1, Lenovo, Rubik's**". I start recording.
2. On the Lenovo, mix the cube (the 8 turns).
3. Open the app → **Solve** tab → **Quick Scan**. Do what the screen asks (show it the corners). If it asks you to fix a color, fix it. Press **Accept**.
4. Look: **does the cube on screen match your real cube?** Tell me "**yes**" or "**no**", plus anything odd.
5. Solve it, mix it again with the same 8 turns, and repeat until you have done it 5 times.
6. Then do the same 5 times with the **GAN cube**.
7. Then do all of it again on the **Galaxy**.

**While doing this, watch for one thing:** when Quick Scan asks you to show a corner again, does the corner it asks for make sense? If not, say "**photo**" and I'll take a screenshot.

---

## Part 2: Recognize a case (about 15 min, Rubik's cube)

**Why:** the app should name the pattern on the top of the cube, in the right order, in under 1 second.

For each one: say "**start**", set up the cube, then go to **Library** → button at the top → **Recognize case**.

| # | Set up the cube (from solved, white top, green front) | The app should say |
|---|---|---|
| 1 | R U2 R' U' R U' R' | **Sune** |
| 2 | R U R' U' R' F R2 U' R' U' R U R' F' | **T-perm** |
| 3 | nothing (solved cube) | top already solved |
| 4 | the 8-turn mix | not ready for this (the cube isn't near the end) |

For **#1 and #2**: when the app shows its picture of the top, say "**photo**". Then take a photo of the **real top of the cube** with your phone and send it to me. I compare the two.

Do it on both devices.

---

## Part 3: "Start from my cube" on the Welcome screen (about 15 min, Lenovo)

**Why:** a new user can scan their cube and the app suggests where to start.

Tell me "**part 3**" and I'll reset the app so the Welcome screen shows.

1. Mix with **R U2 R' U' R U' R'**. Press **Start from my cube** and scan. **Does it suggest a lesson near the end?** Say "photo".
2. Same with a **solved** cube. **Does it say it's solved?**
3. Start the scan, but press **Enter colors** instead. **Does the color editor open?**
4. I'll switch off camera permission. Press **Start from my cube** again. **Does the app explain it and still let you type colors?**

---

## Part 4: Retaking faces and small checks (about 25 min)

Use **Solve** tab → **Scan my cube** (the scan that does all 6 sides). Use any mixed cube.

On the screen at the end that shows all 6 sides:

1. Choose to re-photograph **one** side. **Does the title stay visible? No flicker?**
2. Choose to re-photograph **two** sides, then press back after the first. **Do you land back on the 6-sides screen?**
3. While re-photographing, press **Enter colors**. **Does the editor open?**
4. **Sound:** with the volume up, **do you hear a click** on each photo?
5. **Lenovo only:** on the 6th side, while it shows the frozen picture, turn the tablet sideways. **One small vibration, then the summary screen?**
6. Do one full scan under a **yellow bulb** and one in **dim light**. **Are red and orange ever swapped?** Say "photo" at the end.
7. Press **Accept** on a mixed cube. **Does it go straight to the solution?** Then do it with a solved cube. **Does it say "Already solved"?**
8. Repeat 1–3 on the Galaxy. **Does it fit without scrolling?**

---

## When you finish a part

Just say "**part N done**". I'll save the evidence, score it, and tell you the result. At the end I'll put everything into one pull request and close the matching to-do items.

