/*
 * The pose and action library: reading it, and turning an entry into the pose keys the bridge
 * takes.
 *
 * A pose is a set of bones. An action is a list of steps, each a pose at an offset in frames
 * from where the action starts. A step names a library pose, or the step before it ("previous"),
 * and changes some bones on top; a bone it sets replaces that bone whole.
 *
 * Everything is stored facing the way the model faces at rest, for a right-handed character:
 * left side forward in a stance, right hand the strong one. Turning is the anchor's r.y
 * (positive turns the character to its own left); mirroring swaps left and right.
 */

import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const FILE = join(dirname(fileURLToPath(import.meta.url)), "library.json");

export async function loadLibrary()
{
    return JSON.parse(await readFile(FILE, "utf8"));
}

/** What a model needs to choose an entry: names, what each is for, how long, where it hits. */
export function describeLibrary(library)
{
    const poses = Object.entries(library.poses).map(([name, pose]) => ({ name, description: pose.description }));
    const actions = Object.entries(library.actions).map(([name, action]) => ({
        name,
        description: action.description,
        cadence: action.cadence,
        frames: lastOffset(action),
        from: action.from,
        to: action.to,
        ...(action.contacts ? { contacts: action.contacts } : {}),
    }));

    return { poses, actions };
}

export function getPose(library, name)
{
    const pose = library.poses[name];

    if (!pose)
    {
        throw new Error(`There is no pose '${name}'. The library has: ${Object.keys(library.poses).join(", ")}`);
    }

    return pose;
}

export function getAction(library, name)
{
    const action = library.actions[name];

    if (!action)
    {
        throw new Error(`There is no action '${name}'. The library has: ${Object.keys(library.actions).join(", ")}`);
    }

    return action;
}

/**
 * The keys of an action started at a frame: [{frame, pose}], ready for set_pose_keys.
 * options: turn (degrees added to the anchor's r.y), mirror (swap left and right).
 */
export function expandAction(library, name, frame, options = {})
{
    const action = getAction(library, name);
    const keys = [];
    let previous = null;

    for (const step of action.steps)
    {
        let bones;

        if (step.pose === "previous")
        {
            if (!previous)
            {
                throw new Error(`Action '${name}' starts with a step that builds on the one before it`);
            }

            bones = clone(previous);
        }
        else
        {
            bones = step.pose ? clone(getPose(library, step.pose).bones) : {};
        }

        for (const [bone, transform] of Object.entries(step.set || {}))
        {
            bones[bone] = clone(transform);
        }

        previous = bones;
        keys.push({ frame: frame + step.at, pose: place(bones, options) });
    }

    return keys;
}

export function expandPose(library, name, frame, options = {})
{
    return [{ frame, pose: place(clone(getPose(library, name).bones), options) }];
}

/** The frames of an action's contacts, once it has been started at a frame. */
export function contactFrames(library, name, frame)
{
    return (getAction(library, name).contacts || []).map((offset) => frame + offset);
}

function lastOffset(action)
{
    return action.steps[action.steps.length - 1].at;
}

function place(bones, options)
{
    let placed = options.mirror ? mirror(bones) : bones;

    if (options.turn)
    {
        placed = clone(placed);
        placed.anchor = placed.anchor || {};
        placed.anchor.r = placed.anchor.r || [0, 0, 0];
        placed.anchor.r[1] += options.turn;
    }

    return placed;
}

/**
 * The same pose for the other hand. Reflecting across the body's middle swaps the left and
 * right bones and reverses every turn and tilt (r.y, r.z) and every sideways shift (t.x).
 */
export function mirror(bones)
{
    const out = {};

    for (const [bone, transform] of Object.entries(bones))
    {
        const name = bone.startsWith("left_") ? "right_" + bone.slice(5) : bone.startsWith("right_") ? "left_" + bone.slice(6) : bone;
        const flipped = {};

        if (transform.r) flipped.r = [transform.r[0], -transform.r[1], -transform.r[2]];
        if (transform.t) flipped.t = [-transform.t[0], transform.t[1], transform.t[2]];
        if (transform.s) flipped.s = [...transform.s];

        out[name] = flipped;
    }

    return out;
}

function clone(value)
{
    return JSON.parse(JSON.stringify(value));
}
