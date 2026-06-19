'use strict';

const { add } = require('./index');
const assert = require('assert');

assert.strictEqual(add(2, 3), 5);
console.log('Node sample tests passed');
