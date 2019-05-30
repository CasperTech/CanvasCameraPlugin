import { BarcodeResult } from './BarcodeResult';
export interface CanvasCameraData {
    output?: {
        codes?: BarcodeResult[];
        images?: {
            fullsize?: {
                data: string;
                rotation: number;
                timestamp: number;
            };
            thumbnail?: any[];
        };
    };
}
