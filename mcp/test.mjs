/*
 * Checks the MCP server against a stand-in for the game's bridge:
 *
 *     node mcp/test.mjs
 *
 * It starts a small HTTP server that answers like the bridge, points the MCP server at it, and
 * walks through a session the way a client would.
 */

import { spawn } from "node:child_process";
import { mkdtemp, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const TOKEN = "test-token";
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
const seen = [];
let failures = 0;

function check(name, ok, detail)
{
    if (!ok) failures += 1;

    console.log((ok ? "ok   " : "FAIL ") + name + (ok || detail === undefined ? "" : "  -> " + JSON.stringify(detail)));
}

const game = createServer((request, response) =>
{
    let body = "";

    request.on("data", (chunk) => body += chunk);
    request.on("end", () =>
    {
        const answer = (status, type, data) =>
        {
            response.writeHead(status, { "Content-Type": type, Connection: "close" });
            response.end(data);
        };

        if (request.headers.authorization !== "Bearer " + TOKEN)
        {
            return answer(401, "application/json", "{\"error\":\"Missing or wrong token\"}");
        }

        seen.push({ method: request.method, url: request.url, body: body ? JSON.parse(body) : null });

        if (request.url.startsWith("/render")) return answer(200, "image/png", PNG);
        if (request.url === "/status") return answer(200, "application/json", JSON.stringify({ camera: { x: -6.2942, y: 65.69, z: 26.8165, yaw: -513.02, pitch: 11.93 } }));
        if (request.url === "/film") return answer(200, "application/json", JSON.stringify({
            id: "Test", frames: 98, cameraClips: 1,
            replays: [{ index: 0, name: "Tester", x: -5.3973, y: 64, z: 25.2216, yaw: -114.21, bodyYaw: -146.016, tracks: [
                { kind: "entity", id: "x", keys: 1 }, { kind: "entity", id: "yaw", keys: 3 }, { kind: "property", id: "pose", keys: 28 },
            ] }],
        }));
        if (request.url === "/film/actors/9/rig") return answer(400, "application/json", "{\"error\":\"There is no actor 9; the film has 1\"}");

        answer(200, "application/json", JSON.stringify({ ok: true }));
    });
});

await new Promise((resolve) => game.listen(0, "127.0.0.1", resolve));

const folder = await mkdtemp(join(tmpdir(), "dantcimation-"));
const bridgeFile = join(folder, "bridge.json");

await writeFile(bridgeFile, JSON.stringify({ url: "http://127.0.0.1:" + game.address().port, token: TOKEN }));

const server = spawn(process.execPath, [join(HERE, "server.mjs")], { env: { ...process.env, DANTCIMATION_BRIDGE_FILE: bridgeFile }, stdio: ["pipe", "pipe", "inherit"] });
const waiting = new Map();
let nextId = 1;

createInterface({ input: server.stdout }).on("line", (line) =>
{
    const message = JSON.parse(line);

    waiting.get(message.id)?.(message);
    waiting.delete(message.id);
});

function request(method, params)
{
    const id = nextId++;

    server.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");

    return new Promise((resolve) => waiting.set(id, resolve));
}

function call(name, args)
{
    return request("tools/call", { name, arguments: args });
}

const init = await request("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test", version: "0" } });

check("initialize echoes a protocol it knows", init.result?.protocolVersion === "2025-06-18", init);
check("initialize offers tools and instructions", !!init.result?.capabilities?.tools && init.result.instructions.includes("get_animation_guide"));

const old = await request("initialize", { protocolVersion: "1999-01-01" });

check("an unknown protocol gets the newest one", old.result?.protocolVersion === "2025-06-18", old);

server.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");

check("ping", JSON.stringify((await request("ping")).result) === "{}");

const list = await request("tools/list");
const names = list.result.tools.map((entry) => entry.name);

check("tools/list has the tools", names.includes("set_pose_keys") && names.includes("render_frames") && names.length === 31, names);
check("every tool has a schema and a description", list.result.tools.every((entry) => entry.inputSchema?.type === "object" && entry.description.length > 20));

const guide = await call("get_animation_guide", {});

check("the guide is served", guide.result.content[0].text.includes("Anticipation"));

const film = JSON.parse((await call("get_film", {})).result.content[0].text);

check("get_film drops the still entity tracks", film.replays[0].tracks.length === 2, film.replays[0].tracks);

await call("set_pose_keys", { actor: 0, keys: [{ frame: 6, pose: { head: { r: [0, 20, 0] } } }] });

let last = seen.at(-1);

check("set_pose_keys posts the keys to the actor", last.method === "POST" && last.url === "/film/actors/0/pose-keys" && last.body.keys[0].frame === 6, last);

await call("set_property_keys", { actor: 1, property: "dca11008/color", keys: [{ frame: 26, value: "#FF000000" }] });
last = seen.at(-1);
check("set_property_keys posts property and keys", last.url === "/film/actors/1/property-keys" && last.body.property === "dca11008/color" && last.body.keys[0].value === "#FF000000", last);

await call("clear_root_keys", { actor: 0, channels: ["pitch"], fromFrame: 1 });
last = seen.at(-1);
check("clear_root_keys sends only what was given", JSON.stringify(last.body) === "{\"fromFrame\":1,\"channels\":[\"pitch\"]}", last);

await call("add_camera_clip", {});
last = seen.at(-1);
check("add_camera_clip with nothing posts an empty body", last.url === "/film/camera/clips" && JSON.stringify(last.body) === "{}", last);

await call("add_camera_move", { keys: [{ frame: 0, fromView: true }, { frame: 12, fov: 50, ease: "quart_out" }] });
last = seen.at(-1);
check("add_camera_move posts the keys", last.url === "/film/camera/moves" && last.body.keys[1].fov === 50, last);

await call("remove_camera_clip", { clip: 2 });
check("remove_camera_clip names the clip in the path", seen.at(-1).url === "/film/camera/clips/2/remove", seen.at(-1));

const shelf = JSON.parse((await call("list_library", {})).result.content[0].text);
const jab = shelf.actions.find((entry) => entry.name === "jab");

check("list_library describes the jab", jab && jab.cadence === "action" && jab.frames === 6 && jab.contacts[0] === 2 && jab.from === "guard", jab);

const applied = JSON.parse((await call("apply_action", { actor: 0, action: "jab", frame: 24, turn: 180 })).result.content[0].text);

last = seen.at(-1);
check("apply_action posts whole poses at the action's frames", last.url === "/film/actors/0/pose-keys" && last.body.keys.map((key) => key.frame).join() === "24,26,28,30" && last.body.keys[1].pose.left_elbow.r[0] === 4, last.body.keys.map((key) => key.frame));
check("apply_action turns the anchor", last.body.keys[0].pose.anchor.r[1] === 150, last.body.keys[0].pose.anchor);
check("apply_action says where it hits and where it ends", applied.contacts[0] === 26 && applied.endsAt === 30 && applied.endsIn === "guard", applied);

await call("apply_action", { actor: 0, action: "jab", frame: 0, mirror: true });
last = seen.at(-1);
check("mirror swaps the arms and reverses the turns", last.body.keys[1].pose.right_elbow.r[0] === 4 && last.body.keys[1].pose.anchor.r[1] === 30 && last.body.keys[1].pose.right_arm.r[1] === -42, last.body.keys[1].pose);

const entry = JSON.parse((await call("get_library_entry", { name: "cross" })).result.content[0].text);

check("get_library_entry resolves a step that builds on the one before", entry.steps[2].at === 4 && entry.steps[2].pose.right_elbow.r[0] === 10 && entry.steps[2].pose.left_elbow.r[0] === 136, entry.steps[2]);
check("an unknown action is explained", (await call("apply_action", { actor: 0, action: "nope", frame: 0 })).result.content[0].text.includes("The library has"));

const facing = JSON.parse((await call("get_facing_turn", { actor: 0 })).result.content[0].text);

check("get_facing_turn matches the turn found on renders in the game", Math.abs(facing.turn - (-175.4)) < 0.2, facing);

await call("get_property_keys", { actor: 1, property: "dca11008/color", fromFrame: 20 });
check("get_property_keys encodes the path", seen.at(-1).url === "/film/actors/1/property-keys?property=dca11008%2Fcolor&fromFrame=20", seen.at(-1));

await call("validate_film", { cadence: "action" });
check("validate_film passes the cadence", seen.at(-1).url === "/film/validate?cadence=action", seen.at(-1));

const sheet = await call("render_frames", { frames: [0, 26, 27], columns: 3 });

check("render_frames asks for the sheet", seen.at(-1).url === "/render?frames=0,26,27&columns=3", seen.at(-1));
check("render_frames returns a picture", sheet.result.content[0].type === "image" && sheet.result.content[0].mimeType === "image/png" && Buffer.from(sheet.result.content[0].data, "base64").equals(PNG));

const bad = await call("get_rig", { actor: 9 });

check("the game's complaint comes back as a tool error", bad.result.isError === true && bad.result.content[0].text === "There is no actor 9; the film has 1", bad);
check("an unknown tool is a protocol error", (await call("nope", {})).error?.code === -32602);
check("an unknown method is a protocol error", (await request("resources/list")).error?.code === -32601);

await writeFile(bridgeFile, JSON.stringify({ url: "http://127.0.0.1:" + game.address().port, token: "stale" }));
check("a stale token is explained", (await call("get_status", {})).result.content[0].text.includes("refused the token"));

await writeFile(bridgeFile, JSON.stringify({ url: "http://127.0.0.1:1", token: TOKEN }));
check("a game that is not running is explained", (await call("get_status", {})).result.content[0].text.includes("Could not reach Minecraft"));

server.kill();
game.close();
console.log(failures === 0 ? "\nall passed" : "\n" + failures + " failed");
process.exit(failures === 0 ? 0 : 1);
