import * as cordova from 'cordova';
import {CanvasCameraOptions} from './CanvasCameraOptions';
import {CanvasCameraData} from './CanvasCameraData';
import {BarcodeResult} from './BarcodeResult';

const exec = require('cordova/exec');

class CanvasCamera
{
    nativeClass: 'CanvasCamera';

    private onImage?: (data: string) => void;
    private onBarcodeResult?: (codes: BarcodeResult[]) => void;

    constructor()
    {

    }

    setCallbacks(img: (data: string) => void, codes: (codes: BarcodeResult[]) => void)
    {
        this.onImage = img;
        this.onBarcodeResult = codes;
    }
    clearCallbacks()
    {
        delete this.onImage;
        delete this.onBarcodeResult;
    }

    start(options: CanvasCameraOptions): Promise<void>
    {
        console.log('CanvasCamera: Start');
        return new Promise<void>((resolve, reject) =>
        {
            let returned: boolean = false;
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('STARTING');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            console.log('===========');
            try
            {
                /*
                exec((data: any) =>
                {
                    console.log('GOT RESULT');
                    if (!returned)
                    {
                        returned = true;
                        resolve();
                    }
                    this.onCapture(data);
                }, (err: any) =>
                {
                    console.log('GOT ERROR');
                    console.log(err);
                    if (!returned)
                    {
                        returned = true;
                        reject(err);
                    }
                }, this.nativeClass, 'startCapture', []);
                 */

                exec(function(data: any) {
                    console.log('GOT DATA');
                }.bind(this), function(error: any) {
                    console.log('ERROR!');
                    console.error(error);
                }.bind(this), this.nativeClass, 'startCapture', [options]);
            }
            catch(err)
            {
                returned = true;
                reject(err);
            }
        });
    }

    setScan(scan: boolean)
    {
        return new Promise<void>((resolve, reject) =>
        {
            let returned: boolean = false;
            cordova.exec((data: CanvasCameraData) =>
            {
                if (!returned)
                {
                    returned = true;
                    resolve();
                }
            }, (err) =>
            {
                if (!returned)
                {
                    returned = true;
                    reject(err);
                }
            }, this.nativeClass, 'setScan', [scan]);
        });
    }
    setCapture(capture: boolean)
    {
        return new Promise<void>((resolve, reject) =>
        {
            let returned: boolean = false;
            cordova.exec((data: CanvasCameraData) =>
            {
                if (!returned)
                {
                    returned = true;
                    resolve();
                }
            }, (err) =>
            {
                if (!returned)
                {
                    returned = true;
                    reject(err);
                }
            }, this.nativeClass, 'setCapture', [capture]);
        });
    }

    onCapture(data: CanvasCameraData)
    {
        if (data.output)
        {
            if (data.output.images && data.output.images.fullsize && data.output.images.fullsize.data)
            {
                if (this.onImage)
                {
                    this.onImage(data.output.images.fullsize.data);
                }
            }
            if (data.output.codes)
            {
                if (this.onBarcodeResult)
                {
                    this.onBarcodeResult(data.output.codes);
                }
            }
        }
    }
}

module.exports = new CanvasCamera();
//exports.CanvasCamera = CanvasCamera;