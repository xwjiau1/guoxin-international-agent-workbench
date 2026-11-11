import {defineConfig} from '@playwright/test';
// Application services are started by Docker Compose, never by the test runner.
export default defineConfig({testDir:'./tests',fullyParallel:false,use:{baseURL:process.env.TEST_BASE_URL||'http://127.0.0.1:5173',channel:'msedge',headless:true,viewport:{width:1440,height:960},actionTimeout:30000,screenshot:'only-on-failure',trace:'retain-on-failure'},reporter:'list'});
