package com.ai.android.service;

interface IShellService {
    void destroy() = 16777114;
    String execute(String command, int timeoutSec) = 1;
}
