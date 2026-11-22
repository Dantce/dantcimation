#!/usr/bin/env node
/*
 * Dantcimation MCP server.
 *
 * Speaks the Model Context Protocol on stdin/stdout and turns each tool call into a request to
 * the bridge that the Dantcimation mod opens inside Minecraft (see
 * src/client/java/dantcimation/bridge). It has no dependencies: Node 18 or newer is all it needs.
 *
 * The bridge writes its address and a token for this launch of the game to
 * run/config/bbs/dantcimation/bridge.json. That file is read again on every call, so the game
 * can be restarted without restarting this server.
 */

import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

import { contactFrames, describeLibrary, expandAction, expandPose, getAction, getPose, loadLibrary } from "./library.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const BRIDGE_FILE = process.env.DANTCIMATION_BRIDGE_FILE || join(HERE, "..", "run", "config", "bbs", "dantcimation", "bridge.json");
const GUIDE_FILE = join(HERE, "guide.md");
const PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const SERVER = { name: "dantcimation", version: "0.5.0" };

const INSTRUCTIONS = "Dantcimation animates characters in the BBS film that is open in Minecraft's film editor. "
    + "Call get_animation_guide once before animating: it holds the timing rules, the rig's rotation "
    + "conventions and the working method. Frames are whole numbers at 24 fps; rotations are degrees. "
    + "After writing keys, call render_frames and look at the result before telling the user it is done.";

/* Tools */

const actor = { type: "integer", minimum: 0, description: "The actor's index in the film, from get_film." };
const frame = { type: "integer", minimum: 0, description: "A frame at 24 fps." };
const range = {
    fromFrame: { type: "integer", minimum: 0, description: "First frame of the range. Default 0." },
    toFrame: { type: "integer", minimum: 0, description: "Last frame of the range, included. Default: the end." },
};
const vector = { type: "array", items: { type: "number" }, minItems: 3, maxItems: 3 };

const placing = {
    turn: { type: "number", description: "Degrees to turn the whole character, added to the anchor's r.y; positive turns it to its own left. Library entries face the way the model faces at rest, so a character that shows its back to the camera needs about 180. Find the value once per actor with a render and reuse it." },
    mirror: { type: "boolean", description: "Swap left and right: a southpaw stance, a wave with the other hand." },
};

function tool(name, description, properties, required, run)
{
    return { name, description, inputSchema: { type: "object", properties, required, additionalProperties: false }, run };
}

const TOOLS = [
    tool("get_animation_guide",
        "The Dantcimation animation guide: the timing rules every key must follow, which way each bone of the player rig rotates, how to build an action, how to build impact frames, and the method to work by. Read it once before animating.",
        {}, [],
        async () => text(await readFile(GUIDE_FILE, "utf8"))),

    tool("get_status",
        "Whether Minecraft is running with a world and a film open, the versions in play, and where the view camera is (x, y, z, yaw, pitch).",
        {}, [],
        async () => json(await bridge("GET", "/status"))),

    tool("list_models",
        "The ids of the models an actor can be made from, such as player/steve_bends.",
        {}, [],
        async () => json(await bridge("GET", "/models"))),

    tool("get_film",
        "The open film: its length in frames, how many camera clips it has, and each actor with its index, model, starting position and the tracks that hold keys.",
        {}, [],
        async () => json(compactFilm(await bridge("GET", "/film")))),

    tool("validate_film",
        "Check every pose track against the timing and curve rules. Returns the violations, each with its rule, frame and what is wrong; an empty list means the film passes.",
        { cadence: { type: "string", enum: ["acting", "action"], description: "acting: poses on 3s. action: poses on 2s. Default acting." } }, [],
        async (a) => json(await bridge("GET", "/film/validate?cadence=" + (a.cadence || "acting")))),

    tool("add_actor",
        "Add an actor to the film. Give 'model' for a character, or 'form' (a form as BBS saves it) for anything else, such as a billboard backdrop. Without a position it stands where the player is. Returns the new actor's index.",
        {
            model: { type: "string", description: "A model id from list_models. Default player/steve. Use player/steve_bends when elbows and knees must bend." },
            form: { type: "object", description: "A whole form as BBS saves it, for example {\"id\": \"bbs:billboard\", \"texture\": \"assets:textures/pixel.png\"}. Used instead of 'model'." },
            name: { type: "string" },
            x: { type: "number" }, y: { type: "number" }, z: { type: "number" },
            yaw: { type: "number", description: "Facing in degrees, Minecraft's convention (0 faces +z)." },
        }, [],
        async (a) => json(await bridge("POST", "/film/actors", a))),

    tool("remove_actor",
        "Take an actor out of the film, with everything keyed on it. The actors after it move down one index.",
        { actor }, ["actor"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/remove`, {}))),

    tool("get_rig",
        "The bones of an actor's model and what each one hangs from. Pose keys name these bones.",
        { actor }, ["actor"],
        async (a) => json(await bridge("GET", `/film/actors/${a.actor}/rig`))),

    tool("get_form",
        "An actor's form as BBS saves it, and the paths of the properties that can be keyed on it. A body part's properties sit under the part's id, such as dca11008/color.",
        { actor }, ["actor"],
        async (a) => json(await bridge("GET", `/film/actors/${a.actor}/form`))),

    tool("set_form",
        "Replace an actor's form. Its keys stay; those whose property the new form lacks stop doing anything.",
        { actor, form: { type: "object", description: "A whole form as BBS saves it." } }, ["actor", "form"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/form`, { form: a.form }))),

    tool("set_pose_keys",
        "Write whole-pose keys on a character. Each key replaces the pose at its frame and holds until the next key (stepped); a bone the key does not mention is at rest. Keys at frames that already have one overwrite it.",
        {
            actor,
            keys: {
                type: "array", minItems: 1,
                items: {
                    type: "object",
                    properties: {
                        frame,
                        pose: {
                            type: "object",
                            description: "Bone name to transform. r: rotation in degrees [x, y, z]. t: translation in model pixels. s: scale.",
                            additionalProperties: {
                                type: "object",
                                properties: { r: vector, t: vector, s: vector },
                                additionalProperties: false,
                            },
                        },
                    },
                    required: ["frame", "pose"],
                },
            },
        }, ["actor", "keys"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/pose-keys`, { keys: a.keys }))),

    tool("clear_pose_keys",
        "Remove an actor's pose keys in a frame range.",
        { actor, ...range }, ["actor"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/pose-keys/clear`, pick(a, "fromFrame", "toFrame")))),

    tool("set_root_keys",
        "Key where an actor stands and which way it faces. Only what a key names is written. Keys are stepped.",
        {
            actor,
            keys: {
                type: "array", minItems: 1,
                items: {
                    type: "object",
                    properties: {
                        frame,
                        x: { type: "number" }, y: { type: "number" }, z: { type: "number" },
                        yaw: { type: "number", description: "Turns the body and head too unless bodyYaw or headYaw is given." },
                        pitch: { type: "number" }, bodyYaw: { type: "number" }, headYaw: { type: "number" },
                    },
                    required: ["frame"],
                },
            },
        }, ["actor", "keys"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/root-keys`, { keys: a.keys }))),

    tool("clear_root_keys",
        "Remove an actor's position and facing keys in a frame range: all of them, or only the named channels.",
        { actor, ...range, channels: { type: "array", items: { type: "string" }, description: "Such as [\"x\", \"y\", \"z\", \"yaw\", \"pitch\"]. Default: all." } }, ["actor"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/root-keys/clear`, pick(a, "fromFrame", "toFrame", "channels")))),

    tool("set_property_keys",
        "Key any property of an actor's form other than its pose: visible, color, color_overlay, lighting, transform, or a body part's property such as dca11008/color. Each key holds until the next one. get_form lists the paths.",
        {
            actor,
            property: { type: "string", description: "The property's path, from get_form." },
            keys: {
                type: "array", minItems: 1,
                items: {
                    type: "object",
                    properties: {
                        frame,
                        value: { description: "Written as BBS saves that property: true/false, a number, {\"t\": [..], \"s\": [..], \"r\": [..]} for a transform (r in radians). A colour is \"#RRGGBB\" or \"#AARRGGBB\"." },
                    },
                    required: ["frame", "value"],
                },
            },
        }, ["actor", "property", "keys"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/property-keys`, { property: a.property, keys: a.keys }))),

    tool("clear_property_keys",
        "Remove the keys of one form property in a frame range.",
        { actor, property: { type: "string" }, ...range }, ["actor", "property"],
        async (a) => json(await bridge("POST", `/film/actors/${a.actor}/property-keys/clear`, pick(a, "property", "fromFrame", "toFrame")))),

    tool("get_pose_keys",
        "Read an actor's pose keys back, in the shape set_pose_keys takes: frames, bones, rotations in degrees. Use it to see what is already animated before changing it; a key can be edited and sent straight back.",
        { actor, ...range }, ["actor"],
        async (a) => json(await bridge("GET", `/film/actors/${a.actor}/pose-keys` + query(pick(a, "fromFrame", "toFrame"))))),

    tool("get_property_keys",
        "Read the keys of one form property of an actor, each value as BBS saves it.",
        { actor, property: { type: "string", description: "The property's path, from get_form or get_film." }, ...range }, ["actor", "property"],
        async (a) => json(await bridge("GET", `/film/actors/${a.actor}/property-keys` + query(pick(a, "property", "fromFrame", "toFrame"))))),

    tool("list_library",
        "The pose and action library: ready-made poses, and actions built of poses with their timing already worked out (anticipation, extreme, settle). For each action: its cadence, its length in frames, the pose it starts from and ends in, and the frame offsets of its contacts. Prefer an action from here to posing from scratch, then adjust.",
        {}, [],
        async () => json(describeLibrary(await loadLibrary()))),

    tool("get_library_entry",
        "One library entry in full: a pose's bones, or every step of an action as a whole pose at its frame offset. Use it as a starting point for a variation written with set_pose_keys.",
        { name: { type: "string", description: "A pose or action name from list_library." }, ...placing }, ["name"],
        async (a) =>
        {
            const library = await loadLibrary();
            const options = pick(a, "turn", "mirror");

            if (library.poses[a.name])
            {
                return json({ pose: a.name, description: getPose(library, a.name).description, bones: expandPose(library, a.name, 0, options)[0].pose });
            }

            const action = getAction(library, a.name);

            return json({ action: a.name, description: action.description, cadence: action.cadence, from: action.from, to: action.to, contacts: action.contacts || [], steps: expandAction(library, a.name, 0, options).map((key) => ({ at: key.frame, pose: key.pose })) });
        }),

    tool("get_facing_turn",
        "The 'turn' that makes an actor face the camera squarely, worked out from where the actor stands, which way its body faces and where the camera is. Add 90 or subtract 90 for a profile, add 180 to turn its back. Call it once the camera is locked and reuse the value for every library entry on that actor.",
        { actor }, ["actor"],
        async (a) =>
        {
            const film = await bridge("GET", "/film");
            const status = await bridge("GET", "/status");
            const replay = (film.replays || [])[a.actor];

            if (!replay)
            {
                throw new Error(`There is no actor ${a.actor}; the film has ${(film.replays || []).length}`);
            }

            if (!status.camera)
            {
                throw new Error("Minecraft is not in a world, so there is no camera to face");
            }

            return json({ turn: facingTurn(replay, status.camera) });
        }),

    tool("apply_pose",
        "Write one library pose on a character at a frame.",
        { actor, pose: { type: "string", description: "A pose name from list_library." }, frame, ...placing }, ["actor", "pose", "frame"],
        async (a) =>
        {
            const keys = expandPose(await loadLibrary(), a.pose, a.frame, pick(a, "turn", "mirror"));

            return json({ ...(await bridge("POST", `/film/actors/${a.actor}/pose-keys`, { keys })), frames: [a.frame] });
        }),

    tool("apply_action",
        "Write a library action on a character, starting at a frame. Returns the frames it keyed, the frames of its contacts (where an impact frame or a camera punch-in belongs), and the frame it ends on, which is where the next action can start: an action that starts on the frame the last one ended replaces that pose with its own anticipation, so punches chain without a pause.",
        { actor, action: { type: "string", description: "An action name from list_library." }, frame: { ...frame, description: "The frame the action starts on." }, ...placing }, ["actor", "action", "frame"],
        async (a) =>
        {
            const library = await loadLibrary();
            const action = getAction(library, a.action);
            const keys = expandAction(library, a.action, a.frame, pick(a, "turn", "mirror"));
            const answer = await bridge("POST", `/film/actors/${a.actor}/pose-keys`, { keys });

            return json({ ...answer, frames: keys.map((key) => key.frame), contacts: contactFrames(library, a.action, a.frame), endsAt: keys[keys.length - 1].frame, endsIn: action.to, cadence: action.cadence });
        }),

    tool("get_camera",
        "The film's camera: its clips in order (index, type, start frame, length in frames, layer, and the clip as BBS saves it), and where the editor's view is now (x, y, z, yaw, pitch, roll, fov).",
        {}, [],
        async () => json(await bridge("GET", "/film/camera"))),

    tool("add_camera_clip",
        "Add a clip to the film's camera. With no arguments it locks the camera where the editor's view is now, for the whole film: do this once the person has framed the shot. Clips that follow one another on the same layer are cuts. A clip starts and ends on a whole tick (20 a second), so a cut lands exactly only on frames that are multiples of 6; the answer says where it landed.",
        {
            type: { type: "string", description: "A BBS camera clip type: idle (holds still, the default), shake, dolly, path, look, orbit and others. Used when 'clip' is not given." },
            clip: { type: "object", description: "A whole clip as BBS saves it, with its 'type' (such as bbs:idle); get_camera shows the shape. Used instead of 'type'." },
            frame: { ...frame, description: "Where the clip starts. Default 0." },
            frames: { type: "integer", minimum: 1, description: "How long it lasts. Default: to the end of the film." },
            layer: { type: "integer", minimum: 0, description: "Default: the first layer free at that time. A clip on a higher layer acts on top of the ones below." },
            fromView: { type: "boolean", description: "Fill the clip from the editor's view. Default true when made by 'type', false when 'clip' is given." },
        }, [],
        async (a) => json(await bridge("POST", "/film/camera/clips", a))),

    tool("add_camera_move",
        "Add a camera move: keys in film frames that the camera eases between. A key sets any of x, y, z, yaw, pitch, roll, fov; a channel no key sets is left to the clip underneath, so keying only fov zooms the existing shot. The move becomes one keyframe clip from the first key to the last.",
        {
            keys: {
                type: "array", minItems: 1,
                items: {
                    type: "object",
                    properties: {
                        frame,
                        x: { type: "number" }, y: { type: "number" }, z: { type: "number" },
                        yaw: { type: "number" }, pitch: { type: "number" }, roll: { type: "number" },
                        fov: { type: "number", description: "Field of view in degrees. Smaller is tighter." },
                        fromView: { type: "boolean", description: "Take everything this key does not set from the editor's view." },
                        ease: { type: "string", description: "How the camera leaves this key for the next: cubic_inout (default), cubic_out, quart_out, quint_out, exp_out, back_out, sine_inout, or constant for a cut. linear and elastic are refused." },
                    },
                    required: ["frame"],
                },
            },
            frame: { ...frame, description: "Where the clip starts. Default: the first key." },
            frames: { type: "integer", minimum: 1, description: "How long the clip lasts. Default: to the last key." },
            layer: { type: "integer", minimum: 0 },
        }, ["keys"],
        async (a) => json(await bridge("POST", "/film/camera/moves", a))),

    tool("remove_camera_clip",
        "Remove one clip from the film's camera. The clips after it move down one index.",
        { clip: { type: "integer", minimum: 0, description: "The clip's index, from get_camera." } }, ["clip"],
        async (a) => json(await bridge("POST", `/film/camera/clips/${a.clip}/remove`, {}))),

    tool("clear_camera",
        "Remove every clip from the film's camera.",
        {}, [],
        async () => json(await bridge("POST", "/film/camera/clear", {}))),

    tool("snap_keys_to_grid",
        "Move every key in the film that sits on a frame to the exact tick the tools write keys at (a hair before the frame, so the frame shows it). Needed once for films keyed by hand in the editor or by an older version of the mod; harmless to repeat. Returns how many keys moved.",
        {}, [],
        async () => json(await bridge("POST", "/film/regrid", {}))),

    tool("seek",
        "Move the film editor's playhead, so the person watching sees that frame.",
        { frame }, ["frame"],
        async (a) => json(await bridge("POST", "/film/seek", { frame: a.frame }))),

    tool("render_frames",
        "A picture of the film editor's preview: the current view, or a labelled contact sheet of the frames asked for. This is how to see what was animated. The film editor must be open on screen in Minecraft.",
        {
            frames: { type: "array", items: frame, maxItems: 24, description: "Frames to put on the sheet. Leave out for the view as it is now." },
            columns: { type: "integer", minimum: 1, maximum: 8 },
            width: { type: "integer", minimum: 64, maximum: 1920, description: "Width of one frame on the sheet, in pixels. Default 320." },
        }, [],
        async (a) =>
        {
            const query = [];

            if (a.frames && a.frames.length) query.push("frames=" + a.frames.join(","));
            if (a.columns) query.push("columns=" + a.columns);
            if (a.width) query.push("width=" + a.width);

            const png = await bridge("GET", "/render" + (query.length ? "?" + query.join("&") : ""), undefined, true);

            return { content: [{ type: "image", data: png.toString("base64"), mimeType: "image/png" }] };
        }),
];

/* Talking to the game */

class BridgeError extends Error
{}

async function bridge(method, path, body, binary)
{
    let config;

    try
    {
        config = JSON.parse(await readFile(BRIDGE_FILE, "utf8"));
    }
    catch (e)
    {
        throw new BridgeError(`Could not read ${BRIDGE_FILE}. The Dantcimation mod writes it when Minecraft starts; start the game once, or set DANTCIMATION_BRIDGE_FILE to where it is.`);
    }

    let response;

    try
    {
        response = await fetch(config.url + path, {
            method,
            headers: { Authorization: "Bearer " + config.token, "Content-Type": "application/json" },
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: AbortSignal.timeout(60000),
        });
    }
    catch (e)
    {
        throw new BridgeError(`Could not reach Minecraft at ${config.url}. Start the game with the Dantcimation mod, load a world, and open a film in the BBS film editor.`);
    }

    if (!response.ok)
    {
        const said = await response.text();

        if (response.status === 401 || response.status === 403)
        {
            throw new BridgeError("Minecraft refused the token. The game was probably restarted while this call was on its way; try again.");
        }

        throw new BridgeError(errorText(said) || `Minecraft answered ${response.status}`);
    }

    return binary ? Buffer.from(await response.arrayBuffer()) : await response.json();
}

function errorText(said)
{
    try
    {
        const parsed = JSON.parse(said);

        return typeof parsed.error === "string" ? parsed.error : said;
    }
    catch (e)
    {
        return said;
    }
}

/** A replay carries dozens of entity channels with one key each; only the ones that move are news. */
function compactFilm(film)
{
    for (const replay of film.replays || [])
    {
        replay.tracks = (replay.tracks || []).filter((track) => track.kind !== "entity" || track.keys > 1);
    }

    return film;
}

/**
 * Minecraft's yaw grows clockwise seen from above and 0 looks along +z; the anchor's r.y grows
 * the other way. So the turn is the body's yaw less the yaw of the line to the camera.
 */
export function facingTurn(actor, camera)
{
    const toCamera = Math.atan2(-(camera.x - actor.x), camera.z - actor.z) * 180 / Math.PI;
    const bodyYaw = actor.bodyYaw ?? actor.yaw;
    const turn = ((bodyYaw - toCamera) % 360 + 540) % 360 - 180;

    return Math.round(turn * 10) / 10;
}

function query(values)
{
    const parts = Object.entries(values).map(([name, value]) => name + "=" + encodeURIComponent(value));

    return parts.length ? "?" + parts.join("&") : "";
}

function pick(from, ...names)
{
    const out = {};

    for (const name of names)
    {
        if (from[name] !== undefined) out[name] = from[name];
    }

    return out;
}

function text(string)
{
    return { content: [{ type: "text", text: string }] };
}

function json(value)
{
    return text(JSON.stringify(value));
}

/* The protocol */

async function handle(message)
{
    const { id, method, params } = message;

    switch (method)
    {
        case "initialize":
            return result(id, {
                protocolVersion: PROTOCOLS.includes(params?.protocolVersion) ? params.protocolVersion : PROTOCOLS[0],
                capabilities: { tools: {} },
                serverInfo: SERVER,
                instructions: INSTRUCTIONS,
            });

        case "ping":
            return result(id, {});

        case "tools/list":
            return result(id, { tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) });

        case "tools/call":
        {
            const found = TOOLS.find((candidate) => candidate.name === params?.name);

            if (!found)
            {
                return error(id, -32602, "Unknown tool: " + params?.name);
            }

            try
            {
                return result(id, await found.run(params.arguments || {}));
            }
            catch (e)
            {
                /* What went wrong in the game is for the model to read and act on, so it is a
                 * tool result, not a protocol error */
                return result(id, { content: [{ type: "text", text: e instanceof BridgeError || e?.constructor === Error ? e.message : String(e?.stack || e) }], isError: true });
            }
        }

        default:
            /* A notification has no id and wants no answer */
            return id === undefined ? null : error(id, -32601, "Method not found: " + method);
    }
}

function result(id, value)
{
    return { jsonrpc: "2.0", id, result: value };
}

function error(id, code, message)
{
    return { jsonrpc: "2.0", id, error: { code, message } };
}

function send(message)
{
    if (message)
    {
        process.stdout.write(JSON.stringify(message) + "\n");
    }
}

createInterface({ input: process.stdin }).on("line", async (line) =>
{
    if (!line.trim())
    {
        return;
    }

    let message;

    try
    {
        message = JSON.parse(line);
    }
    catch (e)
    {
        return send(error(null, -32700, "Parse error"));
    }

    send(await handle(message));
});
