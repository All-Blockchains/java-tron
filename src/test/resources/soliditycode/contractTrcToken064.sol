pragma solidity ^0.4.24;
contract transferTokenContract {
    constructor() payable public{}
    function() payable public{}
    function transferTokenTest(address toAddress, uint256 tokenValue, trcToken id) payable public  {
            toAddress.transferToken(tokenValue, id);
    }
    function transferTokenTestIDOverBigInteger(address toAddress) payable public  {
        toAddress.transferToken(1, 9223372036854775809);
    }
    function transferTokenTestValueRandomIdBigInteger(address toAddress) payable public  {
        toAddress.transferToken(1, 36893488147420103233);
    }
    function msgTokenValueAndTokenIdTest() public payable returns(trcToken, uint256){
        trcToken id = msg.tokenid;
        uint256 value = msg.tokenvalue;
        return (id, value);
    }
    function getTokenBalanceTest(address accountAddress) payable public returns (uint256){
        trcToken id = 1000001;
        return accountAddress.tokenBalance(id);
    }
    function getTokenBalnce(address toAddress, trcToken tokenId) public payable returns(uint256){
        return toAddress.tokenBalance(tokenId);
    }
    function transferTokenTestValueMaxBigInteger(address toAddress) payable public  {
    toAddress.transferToken(0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff, 0);
    }
    function transferTokenTestValueOverBigInteger(address toAddress) payable public  {
        toAddress.transferToken(9223372036854775808, 1000001);
    }
    function transferTokenTestValueMaxLong(address toAddress) payable public  {
        toAddress.transferToken(9223372036854775807, 1000001);
    }
    function transferTokenTestSmallerThanZero(address toAddress) payable public  {
    toAddress.transferToken(-9223372036854775809, 1);
    }
}




contract Result {
   Event log(uint256,uint256,uint256);
   constructor() payable public{}
    function() payable public{
         emit log(msg.tokenid,msg.tokenvalue,msg.value);
    }
}