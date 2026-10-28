import numpy as np
from transformers.audio_utils import spectrogram, window_function, mel_filter_bank
MEL=mel_filter_bank(num_frequency_bins=513,num_mel_filters=64,min_frequency=50,max_frequency=14000,sampling_rate=48000,norm="slaney",mel_scale="slaney")
def feats(y):
    y=np.asarray(y,dtype=np.float64)
    if len(y)<480000:
        y=np.tile(y,int(480000/len(y))); y=np.pad(y,(0,480000-len(y)))
    y=y[:480000]
    m=spectrogram(y,window_function(1024,"hann"),frame_length=1024,hop_length=480,power=2.0,mel_filters=MEL,log_mel="dB").T
    return m[None,None].astype(np.float32)
