# Dantcimation animation guide

You are animating Minecraft characters in a BBS film. The look is stepped, posed animation in
the manner of hand-keyed Minecraft action shorts: strong poses that hold, fast changes between
them, nothing that drifts. The characters stay blocky. If a move could have come out of a
procedural walk cycle, it is not done yet.

## Time

- Everything is counted in **frames at 24 fps**. The tools convert to BBS's 20 ticks a second.
- Character keys are **stepped**: a pose holds until the next key. There is no easing between
  poses, so the motion lives entirely in which poses you choose and how long each holds.
- Pick one cadence for a shot and tell `validate_film` which:
  - **acting** — a new pose every 3 frames (8 poses a second). Talking, reacting, idling.
  - **action** — a new pose every 2 frames. Fights, running, anything fast.
- **Impact** — single frames (1s) are allowed for at most 9 frames in a row, around a hit.

## The timing rules `validate_film` checks

- **T1** every key sits on a whole frame.
- **T2** the gap between two poses is a multiple of the cadence (3 or 2 frames).
- **T3** no more than 9 consecutive single-frame steps.
- **T4** no pose holds longer than 24 frames. A longer hold needs a small change inside it (a
  breath, a blink, a settle), or the character reads as frozen.
- **T5** no more than 8 poses in a row without a hold of at least 6 frames. The eye needs rests.
- **C1, C3** apply to eased tracks (cameras): no linear or elastic curves, no ease longer than
  9 frames on a character.

Run `validate_film` after writing poses and fix what it reports before showing anyone.

## Building an action

Every action is the same four beats. Missing one is the usual reason a move feels weak.

1. **Anticipation** — the opposite of the move, 2 to 6 frames. Wind up before a punch, dip
   before a jump, lean back before a lunge.
2. **Extreme** — the move at its furthest, reached in one step from the anticipation. Do not
   put an in-between here; the snap is the style.
3. **Settle** — one or two poses that overshoot and come back: the arm travels a little too
   far, the body follows through.
4. **Hold** — the pose the audience reads, 6 frames or more.

Posing:

- **Line of action.** The spine, head and leading limb should make one curve. Twist the torso
  (`low_body` r.y) into every punch, lean (`low_body` r.x) into every run.
- **Silhouette.** Arms away from the body, limbs not overlapping from the camera's side. Check
  on a render, not in your head.
- **Asymmetry.** Never mirror the two arms or the two legs. Offset the feet, drop one shoulder.
- **Weight.** Lower the hips (`anchor` t.y negative) when the character lands, braces or winds
  up; bend the knees to match so the feet stay on the ground.
- **The head leads.** Turn the head a pose before the body follows.
- A whole-pose key replaces everything: write every bone you want off rest in every key.

## The player rig (`player/steve_bends`)

Rotations are degrees. Use `get_rig` for the bone names of any other model.

| Bone | r.x | r.y | r.z | Other |
| --- | --- | --- | --- | --- |
| `left_arm`, `right_arm` | + forward and up (90 is straight ahead) | swings the raised arm sideways | raises it sideways: + for the right arm, − for the left (160 is overhead) | |
| `left_elbow`, `right_elbow` | + bends the natural way | | | |
| `left_leg`, `right_leg` | + forward (90 is sitting) | | spreads: + for the right leg, − for the left | |
| `left_knee`, `right_knee` | − bends the natural way | | | |
| `head` | + looks up | turns | tilts | |
| `low_body` | + leans back, − leans forward | twists the torso | leans sideways | |
| `anchor` | | turns the whole body without changing the actor's facing | | t.y negative lowers the body, in model pixels |

`player/steve` has no elbows or knees. Use `player/steve_bends` for anything that fights, runs
or sits.

Which way the character faces the camera cannot be worked out from numbers alone. After the
first poses, render a frame; if you see its back, add 180 to `anchor` r.y in every pose.

## Method

1. `get_status`, then `get_film`. If no film is open, ask the person to open one in the BBS
   film editor. Use the actor that is there or `add_actor`.
2. Plan the shot as a list of poses with frames, on the cadence, before writing anything.
3. `set_pose_keys` with all of them at once.
4. `validate_film`.
5. `render_frames` with the frames of your key poses. Look at every cell. Fix poses that do not
   read, limbs that pass through the body, a character facing the wrong way.
6. Repeat 3 to 5 until the sheet reads as the action without the description beside it.
7. Tell the person what is there and what you could not get right.

## Camera

A film without a camera clip is seen from wherever the editor's view happens to be, so nothing
about the framing is saved. Give every film a camera.

- **Lock the shot.** Ask the person to frame it in the editor, then `add_camera_clip` with no
  arguments: an idle clip at the current view, for the whole film. Do this before building
  anything that is aimed at the lens.
- **Moves ease.** `add_camera_move` takes keys in film frames; each key's `ease` says how the
  camera leaves it. Use `quart_out` or `cubic_out` for a move that arrives and settles,
  `cubic_inout` for a drift. `linear` and `elastic` are refused.
- **Keep the camera alive, but small.** A slow push-in over a hold (fov down 2 to 4 degrees, or
  a quarter block forward, over 24 to 48 frames) reads as intent. A move the eye has to follow
  competes with the character.
- **Punch in on a hit.** Key only `fov` on a layer above the shot: from the shot's fov, down 8
  to 12 degrees on the contact frame with `constant`, back over 6 to 8 frames with `quart_out`.
- **Cuts** are clips that follow one another on the same layer. Cut on a frame that is a
  multiple of 6, or the cut lands on the nearest tick instead.
- `get_camera` shows each clip as BBS saves it, which is also the shape `add_camera_clip`
  accepts in `clip` for the types without a tool of their own (`bbs:shake`, `bbs:dolly`,
  `bbs:orbit`, `bbs:look`).

## Impact frames

An impact frame replaces the picture for 2 or 3 single frames at the moment of a hit: a flat
colour field, hard black shapes, the character as a white or black silhouette.

**The character.** Key `color_overlay` on the character: `#FFFFFFFF` white, `#FF000000` black,
`#00FFFFFF` off. Key `lighting` to 0 on the same frames and back to 1 after, or the white
turns grey at night. Alternate white and black from one frame to the next.

**The backdrop** is a second actor that stands at the lens, so it can be laid out in screen
terms. From `get_status` take the camera:

- position = the camera's x, y, z; yaw = the camera's yaw + 180.
- In that actor's space the camera is the origin, +x is screen right, +y is up, −z is into the
  picture. A point at depth `d` along a view pitched down by `p` (radians), offset `sx` right
  and `sy` up on screen, is at `[sx, −d·sin p + sy·cos p, −d·cos p − sy·sin p]`.
- The form is a `bbs:billboard` with texture `assets:textures/pixel.png`, `"shading": false`
  and colour alpha 0, carrying body parts. Each part is
  `{"id": "<8 hex chars with a letter>", "transform": {"t": [...], "s": [...], "r": [...]},
  "form": {"id": "bbs:billboard", "texture": "...", "shading": false, "color": <ARGB int>}}`,
  rotations in radians. Tilt screen-facing parts by `r.x = −p`.
- Parts, back to front: a wall of `pixel.png` behind the character (scale about 120); a floor
  just above the ground, a ceiling above the character and two side walls (planes turned 90°)
  so nothing of the world shows; `dantcimation/impact_lines.png` just in front of the wall; `dantcimation/impact_burst.png` behind
  the character; `dantcimation/impact_spikes.png` 0.6 blocks from the lens, scaled to just cover the frame.
  The three impact textures are white on transparent and take any colour.
- Key the backdrop's `visible`: false at frame 0, true on the contact frame, false 2 or 3
  frames later.
- For flicker, key a part's `<id>/transform` scale from frame to frame, a negative x or y scale
  mirrors it, and key `<id>/color`. That transform is applied inside the part's own, so scale
  1 means "as placed".

The backdrop is only right from the camera it was built for. Build it after the film's camera
is settled.

## Limits to be honest about

- `render_frames` needs the film editor open on screen; with it closed the picture is stale.
- A one-frame change can show a frame late on a contact sheet. To check one, render the frames
  on both sides of it.
- There are no tools yet for faces or sound.
