/* The hub date pick, checked against the list shipped in jokes.js. */
var assert = require("assert");
var fs = require("fs");
var vm = require("vm");
var context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync(__dirname + "/jokes.js", "utf8"), context);

assert.strictEqual(context.jokes.length, 161);
assert.strictEqual(context.jokes[0].q, "Why did the scarecrow win an award?");
assert.strictEqual(context.jokes[0].a, "He was outstanding in his field.");
assert.strictEqual(context.jokes[160].q, "Why did the elephant bring a suitcase to the zoo?");
assert.strictEqual(context.jokes[160].a, "It wanted to pack its trunk.");

var day = new Date(2026, 9, 28, 12, 0, 0);
assert.strictEqual(day.getFullYear(), 2026);
assert.strictEqual(day.getMonth(), 9);
assert.strictEqual(day.getDate(), 28);
var joke = context.jokeForDate(day);
assert.strictEqual(joke.q, "What's a pirate's favorite letter?");
assert.strictEqual(joke.a, "You'd think it's R, but his first love is the C.");

console.log("jokes.test.js ok");
