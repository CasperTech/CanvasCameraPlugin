"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const cordova = require("cordova");
const exec = require('cordova/exec');
class CanvasCamera {
    constructor() {
    }
    setCallbacks(img, codes) {
        this.onImage = img;
        this.onBarcodeResult = codes;
    }
    clearCallbacks() {
        delete this.onImage;
        delete this.onBarcodeResult;
    }
    start(options) {
        console.log('CanvasCamera: Start');
        return new Promise((resolve, reject) => {
            let returned = false;
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('STARTING');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            try {
                exec(function (data) {
                    console.log('GOT DATA');
                }.bind(this), function (error) {
                    console.log('ERROR!');
                    console.error(error);
                }.bind(this), this.nativeClass, 'startCapture', [options]);
            }
            catch (err) {
                returned = true;
                reject(err);
            }
        });
    }
    setScan(scan) {
        return new Promise((resolve, reject) => {
            let returned = false;
            cordova.exec((data) => {
                if (!returned) {
                    returned = true;
                    resolve();
                }
            }, (err) => {
                if (!returned) {
                    returned = true;
                    reject(err);
                }
            }, this.nativeClass, 'setScan', [scan]);
        });
    }
    setCapture(capture) {
        return new Promise((resolve, reject) => {
            let returned = false;
            cordova.exec((data) => {
                if (!returned) {
                    returned = true;
                    resolve();
                }
            }, (err) => {
                if (!returned) {
                    returned = true;
                    reject(err);
                }
            }, this.nativeClass, 'setCapture', [capture]);
        });
    }
    onCapture(data) {
        if (data.output) {
            if (data.output.images && data.output.images.fullsize && data.output.images.fullsize.data) {
                if (this.onImage) {
                    this.onImage(data.output.images.fullsize.data);
                }
            }
            if (data.output.codes) {
                if (this.onBarcodeResult) {
                    this.onBarcodeResult(data.output.codes);
                }
            }
        }
    }
}
module.exports = new CanvasCamera();
//# sourceMappingURL=CanvasCamera.js.map