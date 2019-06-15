'use strict';

var exec = require('cordova/exec');

var CanvasCamera = function() {
    this.canvas = {};
    this.options = {};
    this.onCapture = null;
    this.nativeClass = 'CanvasCamera';
};

CanvasCamera.prototype.start = function(options, onError, onSuccess) {
    this.options = options;

    if (onSuccess && typeof onSuccess === 'function') {
        this.onCapture = onSuccess;
    }

    exec(this.capture.bind(this), function(error) {
        if (onError && typeof onError === 'function') {
            onError(error);
        }
    }.bind(this), this.nativeClass, 'startCapture', [this.options]);
};

CanvasCamera.prototype.capture = function(data)
{
    if (this.onCapture && typeof this.onCapture === 'function')
    {
        this.onCapture(data);
    }
};

module.exports = new CanvasCamera();
