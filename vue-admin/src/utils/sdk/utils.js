import util_web3 from "@/utils/web3/index";
import BigNumber from 'bignumber.js'
import store from "@/store";

const GAS_MULTIPLIER = 1.25;

function getGasEstimate(gas, block){
  const blockLimit = new BigNumber(block.gasLimit);
  if(gas != null){
    gas = blockLimit.dividedBy(new BigNumber(7)).toFixed(0);
  }else{
    if(gas.error) return gas;
    if(gas.code && gas.code == -32000){
      return { error: gas.message}
    }
    gas = new BigNumber(gas).multipliedBy(new BigNumber(GAS_MULTIPLIER));
    if(gas.isGreaterThanOrEqualTo(blockLimit)){
      gas = blockLimit.minus(new BigNumber(1)).toFixed(0);
    }else{
      gas = gas.toFixed(0);
    }
  }
  return gas;

}

const calcGas = async (web3, lastArg, ts) => {
  var block = await web3.eth.getBlock("latest");
  var lastBlock = block.number;
  var gasTracker = store.state.app.config.gasTracker;
  let gasPrice = 0;
  if (
    gasTracker &&
    parseFloat(gasTracker.lastBlock) > parseFloat(lastBlock - 50)
  ) {
    gasPrice = gasTracker.medium;
  } else {
    gasPrice = await web3.eth.getGasPrice();
  }
  let gas = await new Promise((resolve, reject) => {
    ts.estimateGas(
      {
        ...lastArg,
        gasPrice,
      },
      (e, r) => {
        if (e) {
          reject(e);
        } else {
          resolve(r);
        }
      }
    );
  }).catch(e => {
    return { error: e.message }
  });
  gas = getGasEstimate(gas, block);
  if(gas.error) return gas;

  return {
    gasPrice,
    gas,
  };
};

class MyContract {
  constructor(contract, abi, account) {
    this.contract = contract;
    this.abi = abi;
    this.account = account;
    for (const key in this.contract.methods) {
      this[key] = async (...args) => {
        const a = this.abi.abi.find((a) => {
          return a.name === key;
        });
        try {
          const web3 = util_web3.getWeb3();
          if (a.inputs.length === args.length) {
            const result = await this.contract.methods[key](...args).call();
            return result;
          } else {
            const lastArgs = args.pop();
            const ts = this.contract.methods[key](...args);
            const gasResult = await calcGas(web3, lastArgs, ts);
            if(gasResult.error) return gasResult;

            const { gas, gasPrice } = gasResult;
            const result = await ts.send({ ...lastArgs, gasPrice, gas })
            return result;
          }
        } catch (e) {
          return { error: e.message };
        }
      };
    }
  }
}

function checkContractCode(code, address){
  if (!code || code.replace("0x", "").replace(/0/g, "") === "")
      throw new Error(
        `This network does not have this contract at address ${address}`
      );
}


export default {
  async contractAt(abiName, address) {
    if (!store.state.network.connected) {
      return {
        error: "wallet not connected",
      };
    }
    try {
      const account = util_web3.getAccount();
      const abi = require(`./abi/${abiName}.json`);
      const web3 = util_web3.getWeb3();
      var code = await web3.eth.getCode(address);
      checkContractCode(code, address);
      const contract = new web3.eth.Contract(abi.abi, address);
      const myContract = new MyContract(contract, abi, account);
      return myContract;
    } catch (err) {
      return { error: err };
    }
  },
  async getContract(abiName) {
    return this.contractAt(abiName);
  },
  getAccount() {
    return util_web3.getAccount();
  },
  async deploy(abiName, args) {
    try {
      const account = util_web3.getAccount();
      const abi = require(`./abi/${abiName}.json`);
      const web3 = util_web3.getWeb3();
      const contract = new web3.eth.Contract(abi.abi);

      const ts = contract.deploy({ data: abi.bytecode, arguments: args });

      const lastArgs = args.pop();
      const gasResult = await calcGas(web3, lastArgs, ts);
      if(gasResult.error) return gasResult;

      const { gas, gasPrice } = gasResult;
      const result = await ts.send({from: account, gasPrice, gas});
      return result.options;
    } catch (err) {
      return { error: err.message };
    }
  },
};
